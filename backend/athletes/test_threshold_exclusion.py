"""Issue #616: an activity excluded from threshold detection for a field is never a candidate in
any scan - the live window, full and tail replay, suggestions, candidate statuses, the upcoming-
drop near-match rule and decay resimulation - and its decay load comes from its heart rate
instead of its power. Mirrors backend_java's ThresholdExclusionIntegrationTest.
"""

from datetime import UTC, date, datetime, timedelta

from django.test import TestCase

from accounts.models import User
from activities.intensity_histogram import ensure_histogram
from activities.models import Activity, Record

from .decay_load import activity_load, load_by_day_for_athlete
from .models import AcceptedThresholdCandidate, ExcludedThresholdCandidate, ThresholdHistory
from .threshold_decay_recompute import resimulate_suffix
from .threshold_history import (
    candidate_statuses,
    current_window_value,
    rejected_candidates,
    replay_from,
    replay_full_history,
    upcoming_drop,
)

GOOD_DAY = date(2026, 3, 1)
BAD_DAY = date(2026, 3, 10)


def _athlete(email, **overrides):
    defaults = {
        "email": email,
        "password": "x",
        "name": "Exclusion Athlete",
        "threshold_window_days": 30,
        "threshold_sanity_pct": 50,
        "lthr": 160,
    }
    defaults.update(overrides)
    return User.objects.create_user(**defaults)


def _ride(athlete, day, power, heartrate=150, seconds=1200):
    start = datetime(day.year, day.month, day.day, 7, 0, tzinfo=UTC)
    activity = Activity.objects.create(
        athlete=athlete, sport="bike", name="Ride", start_date=start, moving_time=seconds
    )
    Record.objects.bulk_create(
        Record(activity=activity, t=t, ts=start + timedelta(seconds=t), power=power, heartrate=heartrate)
        for t in range(seconds)
    )
    return activity


def _good_and_bad(athlete):
    """A genuine ride implying 247 W and a bad one (an over-reading power meter) implying 304 W."""
    good = _ride(athlete, GOOD_DAY, power=260)
    bad = _ride(athlete, BAD_DAY, power=320, heartrate=170)
    return good, bad


def _exclude(athlete, activity, field="ftp"):
    ExcludedThresholdCandidate.objects.create(athlete=athlete, field=field, activity=activity)


class ExclusionScanTests(TestCase):
    def test_current_window_value_ignores_an_excluded_activity(self):
        athlete = _athlete("exclude-window@example.cc")
        good, bad = _good_and_bad(athlete)
        self.assertEqual(current_window_value(athlete, "ftp", as_of=BAD_DAY).activity_id, bad.id)

        _exclude(athlete, bad)

        self.assertEqual(current_window_value(athlete, "ftp", as_of=BAD_DAY).activity_id, good.id)

    def test_exclusion_is_per_field(self):
        athlete = _athlete("exclude-per-field@example.cc")
        _good, bad = _good_and_bad(athlete)

        _exclude(athlete, bad, field="critical_run_power")

        self.assertEqual(current_window_value(athlete, "ftp", as_of=BAD_DAY).activity_id, bad.id)

    def test_full_and_tail_replay_never_produce_a_row_from_an_excluded_activity(self):
        athlete = _athlete("exclude-replay@example.cc")
        good, bad = _good_and_bad(athlete)
        _exclude(athlete, bad)

        for entries in (replay_full_history(athlete, "ftp"), replay_from(athlete, "ftp", GOOD_DAY)):
            with self.subTest(entries=entries):
                self.assertIn(good.id, {entry.activity_id for entry in entries})
                self.assertNotIn(bad.id, {entry.activity_id for entry in entries})

    def test_rejected_candidates_omits_an_excluded_activity(self):
        # A 10% band against a 247 W profile value rejects the 304 W ride, so it's a suggestion -
        # until it's excluded.
        athlete = _athlete("exclude-rejected@example.cc", threshold_sanity_pct=10, ftp=247)
        _good, bad = _good_and_bad(athlete)
        self.assertIn(bad.id, {c.activity_id for c in rejected_candidates(athlete, "ftp", as_of=BAD_DAY)})

        _exclude(athlete, bad)

        self.assertNotIn(bad.id, {c.activity_id for c in rejected_candidates(athlete, "ftp", as_of=BAD_DAY)})

    def test_candidate_statuses_reports_excluded_with_its_implied_value(self):
        athlete = _athlete("exclude-statuses@example.cc", ftp=247)
        _good, bad = _good_and_bad(athlete)
        _exclude(athlete, bad)

        statuses = {s.activity_id: s for s in candidate_statuses(athlete, "ftp", GOOD_DAY, BAD_DAY)}

        self.assertEqual(statuses[bad.id].status, "excluded")
        self.assertEqual(round(statuses[bad.id].implied_value), 304)

    def test_exclusion_wins_over_an_acceptance(self):
        athlete = _athlete("exclude-beats-accept@example.cc", threshold_sanity_pct=10, ftp=247)
        good, bad = _good_and_bad(athlete)
        AcceptedThresholdCandidate.objects.create(athlete=athlete, field="ftp", activity=bad)
        _exclude(athlete, bad)

        self.assertEqual(current_window_value(athlete, "ftp", as_of=BAD_DAY).activity_id, good.id)
        statuses = {s.activity_id: s.status for s in candidate_statuses(athlete, "ftp", GOOD_DAY, BAD_DAY)}
        self.assertEqual(statuses[bad.id], "excluded")

    def test_an_excluded_ride_no_longer_suppresses_an_upcoming_drop(self):
        """A recent ride implying the current value is both the successor and a near match, which
        suppresses the upcoming-drop warning. Excluded, it must suppress nothing."""
        athlete = _athlete("exclude-upcoming@example.cc", ftp=300, threshold_warning_days=14)
        anchor_day = date(2026, 3, 1)
        ThresholdHistory.objects.create(
            athlete=athlete,
            field="ftp",
            value_numeric=300,
            effective_from=anchor_day,
            current_from=anchor_day,
            kind="manual",
        )
        recent = _ride(athlete, anchor_day + timedelta(days=20), power=316)  # implies ~300
        today = anchor_day + timedelta(days=25)
        self.assertIsNone(upcoming_drop(athlete, "ftp", today=today))

        _exclude(athlete, recent)

        self.assertIsNotNone(upcoming_drop(athlete, "ftp", today=today))


class ExclusionDecayTests(TestCase):
    def test_resimulation_never_anchors_on_an_excluded_activity(self):
        athlete = _athlete(
            "exclude-decay@example.cc",
            threshold_decay_enabled=True,
            threshold_decay_tau_days=25,
            threshold_decay_min_pct=3,
            threshold_decay_max_pct=12,
        )
        good, bad = _good_and_bad(athlete)
        _exclude(athlete, bad)

        resimulate_suffix(athlete, "ftp", GOOD_DAY, end=BAD_DAY + timedelta(days=60))

        sources = set(
            ThresholdHistory.objects.filter(athlete=athlete, field="ftp", kind="measured").values_list(
                "source_activity_id", flat=True
            )
        )
        self.assertEqual(sources, {good.id})

    def test_an_excluded_activity_counts_through_its_heart_rate_in_the_decay_load(self):
        athlete = _athlete("exclude-load@example.cc")
        _good, bad = _good_and_bad(athlete)
        histogram = ensure_histogram(bad)
        with_power = load_by_day_for_athlete(athlete, "ftp", 250, athlete.lthr, BAD_DAY, BAD_DAY)

        _exclude(athlete, bad)
        with_hr = load_by_day_for_athlete(athlete, "ftp", 250, athlete.lthr, BAD_DAY, BAD_DAY)

        hr_only = activity_load(None, histogram.heartrate, 250, athlete.lthr)
        self.assertGreater(hr_only, 0)
        self.assertNotAlmostEqual(with_power[BAD_DAY], with_hr[BAD_DAY])
        # One activity on a fresh series: the EWMA's first day is load / 42.
        self.assertAlmostEqual(with_hr[BAD_DAY], hr_only / 42)
