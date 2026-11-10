"""Integration tests for the decay-enabled wiring (issue #485, slice 3) - ingest, manual edit,
refresh, and rebuild all going through threshold_decay_recompute.resimulate_suffix instead of
the cliff-based current_window_value/_recompute_and_record/replay_full_history_into path, for a
decay-enabled athlete. The pure simulate() mechanics themselves are already covered by
test_threshold_decay.py - this file is about the DB-backed plumbing around it: seeding from the
real ledger, the delete-then-regenerate, set_by attribution, and that a decay-disabled athlete
(the default) is completely unaffected.
"""

from datetime import UTC, date, datetime, timedelta

from django.test import TestCase

from accounts.models import User
from activities.models import Activity, Record

from .models import ThresholdHistory
from .threshold_decay_recompute import resimulate_suffix
from .threshold_history import recompute_for_activity, record_manual_value, refresh_field


def _decay_athlete(**overrides):
    defaults = {
        "email": "decay-athlete@example.cc",
        "password": "x",
        "name": "Decay Athlete",
        "threshold_decay_enabled": True,
        "threshold_window_days": 10,
        "threshold_sanity_pct": 50,
        "threshold_decay_tau_days": 5,
        "threshold_decay_min_pct": 3,
        "threshold_decay_max_pct": 12,
    }
    defaults.update(overrides)
    return User.objects.create_user(**defaults)


def _make_power_activity(athlete, start_date, power, duration_seconds=1200):
    activity = Activity.objects.create(
        athlete=athlete, sport="bike", name="Ride", start_date=start_date, moving_time=duration_seconds
    )
    for t in range(duration_seconds):
        Record.objects.create(activity=activity, t=t, ts=start_date + timedelta(seconds=t), power=power)
    return activity


class IngestWiringTests(TestCase):
    def test_recompute_for_activity_creates_a_measured_anchor_for_a_decay_enabled_athlete(self):
        # recompute_for_activity resimulates the whole suffix through the real today (design doc
        # 4.2) - an activity dated this far in the past (relative to window_days=10) will have
        # long since decayed away from its own anchor value by the time the real clock catches
        # up, so this only checks the FIRST (measured, anchor) row, not the athlete's live value.
        athlete = _decay_athlete()
        activity = _make_power_activity(athlete, datetime(2026, 1, 1, 7, 0, tzinfo=UTC), power=260)

        recompute_for_activity(activity)

        entry = ThresholdHistory.objects.filter(athlete=athlete, field="ftp").order_by("effective_from").first()
        self.assertEqual(entry.kind, "measured")
        self.assertEqual(entry.value_numeric, round(0.95 * 260))
        self.assertEqual(entry.source_activity_id, activity.id)
        self.assertEqual(entry.effective_from, date(2026, 1, 1))

    def test_decay_disabled_athlete_is_unaffected_by_the_new_wiring(self):
        """The default - confirms the branch in recompute_for_activity actually branches."""
        athlete = User.objects.create_user(email="old-path@example.cc", password="x", name="Old Path")
        self.assertFalse(athlete.threshold_decay_enabled)
        activity = _make_power_activity(athlete, datetime(2026, 1, 1, 7, 0, tzinfo=UTC), power=260)

        recompute_for_activity(activity)

        entry = ThresholdHistory.objects.get(athlete=athlete, field="ftp")
        self.assertEqual(entry.kind, "measured")
        self.assertEqual(entry.effective_from, entry.current_from)


class ResimulateSuffixIntegrationTests(TestCase):
    """Exercises resimulate_suffix directly over a multi-day range against real DB state - what
    the (not-yet-built) daily job will eventually do, and what distinguishes this from the pure
    simulate() unit tests, which never touch the database."""

    def test_decayed_rows_accumulate_over_a_multi_day_resimulation(self):
        athlete = _decay_athlete()
        anchor_date = date(2026, 1, 1)
        _make_power_activity(athlete, datetime(2026, 1, 1, 7, 0, tzinfo=UTC), power=260)
        recompute_for_activity(Activity.objects.get(athlete=athlete))

        expiry = anchor_date + timedelta(days=athlete.threshold_window_days + 1)
        end = expiry + timedelta(days=30)
        result = resimulate_suffix(athlete, "ftp", expiry, end=end)

        self.assertTrue(result.changed)
        decayed = ThresholdHistory.objects.filter(athlete=athlete, field="ftp", kind="decayed").order_by(
            "effective_from"
        )
        self.assertGreater(decayed.count(), 0)
        values = [e.value_numeric for e in decayed]
        self.assertEqual(values, sorted(values, reverse=True))  # strictly easing down
        self.assertLess(values[-1], 260)

        athlete.refresh_from_db()
        self.assertEqual(athlete.ftp, values[-1])

    def test_rerates_activities_after_a_retroactive_change(self):
        """Design doc 4.6: stored TSS/IF must not drift from the ledger after a decay step."""
        athlete = _decay_athlete()
        anchor_date = date(2026, 1, 1)
        activity = _make_power_activity(athlete, datetime(2026, 1, 1, 7, 0, tzinfo=UTC), power=260)
        recompute_for_activity(activity)

        expiry = anchor_date + timedelta(days=athlete.threshold_window_days + 1)
        later_activity = _make_power_activity(
            athlete,
            datetime(2026, 1, 1, 7, 0, tzinfo=UTC) + timedelta(days=athlete.threshold_window_days + 20),
            power=185,
        )
        old_tss = later_activity.tss

        resimulate_suffix(athlete, "ftp", expiry, end=expiry + timedelta(days=25))

        later_activity.refresh_from_db()
        self.assertNotEqual(later_activity.tss, old_tss)


class RefreshFieldWiringTests(TestCase):
    def test_refresh_is_append_only_and_emits_a_decayed_row_once_past_expiry(self):
        # refresh_field always resimulates through the real date.today() internally, with no way
        # to inject a fixed "today" - so this brings the ledger up to a known state as of
        # yesterday (relative to the real clock) via a directly-controlled resimulate_suffix
        # call first, then calls the real refresh_field and confirms it only ever appends.
        # Just a few days past expiry (not many) - with tau_days=5, decay flattens out close to
        # its floor quickly, and this test needs refresh's one extra day to still move the
        # rounded value, not land after it's already fully settled.
        athlete = _decay_athlete()
        anchor_date = date.today() - timedelta(days=athlete.threshold_window_days + 3)
        _make_power_activity(athlete, datetime.combine(anchor_date, datetime.min.time(), tzinfo=UTC), power=260)
        yesterday = date.today() - timedelta(days=1)
        resimulate_suffix(athlete, "ftp", anchor_date, end=yesterday)
        rows_before = set(ThresholdHistory.objects.filter(athlete=athlete, field="ftp").values_list("pk", flat=True))
        self.assertGreater(len(rows_before), 1)  # already decaying past expiry

        changed = refresh_field(athlete, "ftp")

        self.assertTrue(changed)
        rows_after = ThresholdHistory.objects.filter(athlete=athlete, field="ftp")
        new_rows = rows_after.exclude(pk__in=rows_before)
        self.assertEqual(new_rows.count(), 1)
        self.assertEqual(new_rows.get().current_from, date.today())
        self.assertEqual(new_rows.get().kind, "decayed")
        # Append-only: every pre-existing row survives untouched.
        self.assertEqual(rows_after.filter(pk__in=rows_before).count(), len(rows_before))


class RecordManualValueWiringTests(TestCase):
    def test_manual_edit_creates_its_own_row_with_set_by_via_resimulation(self):
        athlete = _decay_athlete()
        coach = User.objects.create_user(email="coach@example.cc", password="x", name="Coach", is_coach=True)

        result = record_manual_value(athlete, "ftp", 255, set_by=coach)

        self.assertTrue(result.changed)
        self.assertTrue(result.became_current)
        self.assertFalse(result.is_correction_only)
        entry = ThresholdHistory.objects.get(athlete=athlete, field="ftp")
        self.assertEqual(entry.kind, "manual")
        self.assertEqual(entry.value_numeric, 255)
        self.assertEqual(entry.set_by_id, coach.id)
        athlete.refresh_from_db()
        self.assertEqual(athlete.ftp, 255)

    def test_correction_only_edit_still_uses_the_direct_write_path(self):
        """A backdated edit that never becomes current doesn't need a suffix resimulation - it
        stays on today's direct-write path regardless of threshold_decay_enabled."""
        athlete = _decay_athlete()
        later_date = date(2026, 6, 1)
        record_manual_value(athlete, "ftp", 250, effective_from=later_date, as_of=later_date)

        backdated_result = record_manual_value(
            athlete, "ftp", 230, effective_from=later_date - timedelta(days=5), as_of=later_date
        )

        self.assertTrue(backdated_result.changed)
        self.assertTrue(backdated_result.is_correction_only)
        self.assertFalse(backdated_result.became_current)
        correction_row = ThresholdHistory.objects.get(athlete=athlete, field="ftp", value_numeric=230)
        self.assertTrue(correction_row.is_correction_only)
        # The live value is still the later, non-correction edit - untouched by the backdated one.
        athlete.refresh_from_db()
        self.assertEqual(athlete.ftp, 250)

    def test_preview_rolls_back_everything_including_the_resimulated_rows(self):
        athlete = _decay_athlete()
        before_count = ThresholdHistory.objects.filter(athlete=athlete, field="ftp").count()

        result = record_manual_value(athlete, "ftp", 240, preview=True)

        self.assertTrue(result.changed)
        self.assertEqual(ThresholdHistory.objects.filter(athlete=athlete, field="ftp").count(), before_count)
        athlete.refresh_from_db()
        self.assertIsNone(athlete.ftp)


class RebuildWiringTests(TestCase):
    def test_rebuild_keeps_manual_rows_and_regenerates_measured_and_decayed(self):
        athlete = _decay_athlete()
        manual_date = date(2026, 1, 1)
        # Lower than the activity's own implied value (round(0.95*260)=247) below, so the
        # activity legitimately beats it as an upward reset - otherwise the stronger manual
        # anchor would correctly never be replaced (the 1.1 regression protection), and no
        # "measured" row would ever appear, which isn't what this test is after.
        record_manual_value(athlete, "ftp", 200, effective_from=manual_date, as_of=manual_date)
        activity = _make_power_activity(athlete, datetime(2026, 1, 10, 7, 0, tzinfo=UTC), power=260)
        recompute_for_activity(activity)
        stray_decayed = ThresholdHistory.objects.create(
            athlete=athlete,
            field="ftp",
            value_numeric=240,
            effective_from=date(2026, 2, 1),
            current_from=date(2026, 2, 1),
            kind="decayed",
        )

        from .threshold_history import rebuild_history_stream

        list(rebuild_history_stream(athlete, "ftp"))

        self.assertTrue(ThresholdHistory.objects.filter(pk=ThresholdHistory.objects.get(value_numeric=200).pk).exists())
        self.assertFalse(ThresholdHistory.objects.filter(pk=stray_decayed.pk).exists())
        self.assertTrue(ThresholdHistory.objects.filter(athlete=athlete, field="ftp", kind="measured").exists())
