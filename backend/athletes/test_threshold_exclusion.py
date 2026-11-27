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


def _client(user, scope="activities:read activities:write"):
    from rest_framework.test import APIClient

    from authn.oauth_utils import issue_token_pair

    access_token, _ = issue_token_pair(user, scope=scope)
    client = APIClient()
    client.credentials(HTTP_AUTHORIZATION=f"Bearer {access_token.token}")
    return client


class ThresholdExclusionApiTests(TestCase):
    """The REST API over the exclusion: POST excludes and rebuilds from the activity's date (or
    previews that with nothing saved), DELETE reverses it, GET lists."""

    def setUp(self):
        from uploads.processing import recompute_after_duplicate_change

        self.athlete = _athlete("exclude-api@example.cc")
        self.good, self.bad = _good_and_bad(self.athlete)
        recompute_after_duplicate_change(self.athlete, fields=["ftp"])
        self.athlete.refresh_from_db()
        self.url = f"/v1/athletes/{self.athlete.id}/threshold-exclusions"

    def _ledger(self):
        return sorted(
            ThresholdHistory.objects.filter(athlete=self.athlete, field="ftp").values_list(
                "source_activity_id", "value_numeric", "current_from"
            ),
            key=lambda row: row[2],
        )

    def test_preview_reports_the_effect_and_saves_nothing(self):
        ledger_before = self._ledger()
        self.assertEqual(self.athlete.ftp, 304)

        response = _client(self.athlete).post(
            self.url, {"field": "ftp", "activity_id": self.bad.id, "preview": True}, format="json"
        )

        self.assertEqual(response.status_code, 200, response.content)
        body = response.json()
        self.assertTrue(body["preview"])
        self.assertEqual((body["value_before"], body["value_after"]), (304, 247))
        self.assertIn(self.bad.id, {row["source_activity_id"] for row in body["rows_removed"]})
        self.assertEqual(self._ledger(), ledger_before)
        self.assertFalse(ExcludedThresholdCandidate.objects.exists())
        self.athlete.refresh_from_db()
        self.assertEqual(self.athlete.ftp, 304)

    def test_exclude_rebuilds_the_ledger_and_delete_restores_it(self):
        ledger_before = self._ledger()

        response = _client(self.athlete).post(self.url, {"field": "ftp", "activity_id": self.bad.id}, format="json")

        self.assertEqual(response.status_code, 200, response.content)
        self.assertEqual(response.json()["value_after"], 247)
        self.assertNotIn(self.bad.id, {row[0] for row in self._ledger()})
        self.athlete.refresh_from_db()
        self.assertEqual(self.athlete.ftp, 247)
        exclusion = ExcludedThresholdCandidate.objects.get()
        self.assertEqual(exclusion.set_by_id, self.athlete.id)

        response = _client(self.athlete).delete(f"{self.url}/ftp/{self.bad.id}")

        self.assertEqual(response.status_code, 200, response.content)
        self.assertEqual(response.json()["value_after"], 304)
        self.assertEqual(self._ledger(), ledger_before)
        self.assertFalse(ExcludedThresholdCandidate.objects.exists())

    def test_excluding_twice_changes_nothing_the_second_time(self):
        client = _client(self.athlete)
        client.post(self.url, {"field": "ftp", "activity_id": self.bad.id}, format="json")

        response = client.post(self.url, {"field": "ftp", "activity_id": self.bad.id}, format="json")

        self.assertEqual(response.status_code, 200)
        self.assertEqual((response.json()["rows_removed"], response.json()["rows_added"]), ([], []))
        self.assertEqual(ExcludedThresholdCandidate.objects.count(), 1)

    def test_excluding_removes_an_acceptance_of_the_same_activity(self):
        AcceptedThresholdCandidate.objects.create(athlete=self.athlete, field="ftp", activity=self.bad)

        _client(self.athlete).post(self.url, {"field": "ftp", "activity_id": self.bad.id}, format="json")

        self.assertFalse(AcceptedThresholdCandidate.objects.exists())

    def test_list_filters_by_field_and_activity(self):
        client = _client(self.athlete)
        client.post(self.url, {"field": "ftp", "activity_id": self.bad.id}, format="json")

        everything = client.get(self.url).json()["data"]
        by_activity = client.get(self.url, {"activity_id": self.good.id}).json()["data"]
        by_field = client.get(self.url, {"field": "critical_run_power"}).json()["data"]

        self.assertEqual([(row["field"], row["activity_id"]) for row in everything], [("ftp", self.bad.id)])
        self.assertEqual((by_activity, by_field), ([], []))

    def test_rejects_bad_input(self):
        run = Activity.objects.create(
            athlete=self.athlete, sport="run", name="Run", start_date=datetime(2026, 3, 2, tzinfo=UTC), moving_time=60
        )
        stranger = _athlete("exclude-api-stranger@example.cc")
        theirs = _ride(stranger, GOOD_DAY, power=200)
        client = _client(self.athlete)

        cases = [
            ({"field": "vo2max", "activity_id": self.bad.id}, 400),
            ({"field": "ftp"}, 400),
            ({"field": "ftp", "activity_id": run.id}, 400),  # a run can't define ftp
            ({"field": "ftp", "activity_id": theirs.id}, 404),  # another athlete's activity
            ({"field": "ftp", "activity_id": "act_missing"}, 404),
        ]
        for payload, status in cases:
            with self.subTest(payload=payload):
                self.assertEqual(client.post(self.url, payload, format="json").status_code, status)
        self.assertEqual(client.delete(f"{self.url}/ftp/{self.good.id}").status_code, 404)  # not excluded

    def test_needs_write_access_to_the_athlete(self):
        """Per-athlete access, as for every REST endpoint: neither backend's REST layer checks
        OAuth scopes (only the MCP tools do), so a stranger is refused on access, not scope."""
        outsider = _athlete("exclude-api-outsider@example.cc")
        client = _client(outsider)

        self.assertEqual(client.get(self.url).status_code, 403)
        self.assertEqual(
            client.post(self.url, {"field": "ftp", "activity_id": self.bad.id}, format="json").status_code, 403
        )
        self.assertEqual(client.delete(f"{self.url}/ftp/{self.bad.id}").status_code, 403)
        self.assertFalse(ExcludedThresholdCandidate.objects.exists())

    def test_a_coach_with_write_access_is_recorded_as_set_by(self):
        from accounts.models import UserRelationship
        from authn.jwt_utils import mint_jwt

        coach = _athlete("exclude-api-coach@example.cc", is_coach=True)
        UserRelationship.objects.create(
            owner=self.athlete, grantee=coach, role=UserRelationship.ROLE_COACH, status=UserRelationship.STATUS_ACTIVE
        )
        token, _claims = mint_jwt(sub=coach.id, athlete_id=self.athlete.id, scopes=["activities:write"], expires_in=60)
        from rest_framework.test import APIClient

        client = APIClient()
        client.credentials(HTTP_AUTHORIZATION=f"Bearer {token}")

        response = client.post(self.url, {"field": "ftp", "activity_id": self.bad.id}, format="json")

        self.assertEqual(response.status_code, 200, response.content)
        self.assertEqual(ExcludedThresholdCandidate.objects.get().set_by_id, coach.id)
