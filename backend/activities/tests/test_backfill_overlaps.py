from datetime import UTC, datetime, timedelta
from io import StringIO

from django.core.management import call_command
from django.test import TestCase

from accounts.models import User

from .helpers import _make_activity


class BackfillOverlapsTests(TestCase):
    """Issue #498's backfill command - mirrors backend_java's OverlapBackfillRunner. The
    primary-selection/mark/flag logic itself is covered directly (and far more cheaply) by
    OverlapDetectionTests; this only exercises the command's own report/apply wiring, athlete
    scoping, and the post-apply recompute cascade not crashing."""

    def setUp(self):
        self.athlete = User.objects.create_user(email="backfill-overlaps@example.cc", password="x", name="Athlete")
        self.other_athlete = User.objects.create_user(
            email="backfill-overlaps-other@example.cc", password="x", name="Other Athlete"
        )
        self.start = datetime(2026, 6, 1, 18, 0, 0, tzinfo=UTC)

    def _bike(self, athlete, **kwargs):
        defaults = {"sport": "bike", "elapsed_seconds": 3600, "moving_time": 3600, "avg_hr": None}
        defaults.update(kwargs)
        return _make_activity(athlete, **defaults)

    def _run(self, *args):
        out = StringIO()
        call_command("backfill_overlaps", *args, stdout=out)
        return out.getvalue()

    def test_report_mode_lists_the_pair_but_changes_nothing(self):
        zwift = self._bike(self.athlete, device="Zwift", start_date=self.start)
        garmin = self._bike(self.athlete, device="Garmin", start_date=self.start + timedelta(seconds=5))

        output = self._run()

        self.assertIn(zwift.id, output)
        self.assertIn(garmin.id, output)
        self.assertIn("report-only", output)
        garmin.refresh_from_db()
        zwift.refresh_from_db()
        self.assertIsNone(garmin.primary_activity_id)
        self.assertIsNone(zwift.primary_activity_id)

    def test_apply_mode_with_mark_action_marks_the_lower_ranked_copy(self):
        zwift = self._bike(self.athlete, device="Zwift", start_date=self.start)
        garmin = self._bike(self.athlete, device="Garmin", start_date=self.start + timedelta(seconds=5))

        output = self._run("--mode", "apply")

        self.assertIn("1 marked, 0 flagged", output)
        garmin.refresh_from_db()
        zwift.refresh_from_db()
        self.assertEqual(garmin.primary_activity_id, zwift.id)
        self.assertIsNone(zwift.primary_activity_id)

    def test_apply_mode_with_flag_action_changes_nothing(self):
        zwift = self._bike(self.athlete, device="Zwift", start_date=self.start)
        garmin = self._bike(self.athlete, device="Garmin", start_date=self.start + timedelta(seconds=5))

        output = self._run("--mode", "apply", "--action", "flag")

        self.assertIn("0 marked, 1 flagged", output)
        garmin.refresh_from_db()
        zwift.refresh_from_db()
        self.assertIsNone(garmin.primary_activity_id)
        self.assertIsNone(zwift.primary_activity_id)

    def test_athlete_filter_only_touches_that_athletes_pairs(self):
        self._bike(self.athlete, device="Zwift", start_date=self.start)
        self._bike(self.athlete, device="Garmin", start_date=self.start + timedelta(seconds=5))
        other_zwift = self._bike(self.other_athlete, device="Zwift", start_date=self.start)
        other_garmin = self._bike(self.other_athlete, device="Garmin", start_date=self.start + timedelta(seconds=5))

        output = self._run("--athlete", self.other_athlete.id, "--mode", "apply")

        self.assertIn("1 marked, 0 flagged", output)
        other_garmin.refresh_from_db()
        other_zwift.refresh_from_db()
        self.assertEqual(other_garmin.primary_activity_id, other_zwift.id)

    def test_unknown_athlete_raises(self):
        from django.core.management.base import CommandError

        with self.assertRaises(CommandError):
            self._run("--athlete", "ath_does_not_exist")
