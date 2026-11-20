"""Issue #525: mirrors backend_java's ActivityPostProcessingServiceIntegrationTest."""

from datetime import UTC, datetime, timedelta

from django.test import TestCase

from accounts.models import User
from scheduling.models import ScheduledWorkout
from workouts.models import Workout

from ..activity_post_processing import recompute_after_merge
from ..models import BestEffort, DurationCurve, Record
from .helpers import _make_activity


class ActivityPostProcessingTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(
            email="merge-postprocess@example.cc", password="x", name="Athlete", ftp=200, lthr=160
        )
        self.start = datetime(2026, 6, 1, 7, 0, 0, tzinfo=UTC)

    def _bike_activity(self, **kwargs):
        return _make_activity(self.athlete, sport="bike", start_date=self.start, moving_time=600, **kwargs)

    def _add_records(self, activity, count=600, power=220, heartrate=165):
        for t in range(count):
            Record.objects.create(
                activity=activity,
                t=t,
                ts=activity.start_date + timedelta(seconds=t),
                power=power,
                heartrate=heartrate,
            )

    def test_recomputes_derived_stats_from_rewritten_records(self):
        activity = self._bike_activity()
        self._add_records(activity, power=250)
        self.assertIsNone(activity.max_power)

        recompute_after_merge(activity.id)

        activity.refresh_from_db()
        self.assertEqual(activity.max_power, 250)
        self.assertEqual(activity.avg_power, 250)

    def test_recomputes_avg_and_max_hr_from_rewritten_records(self):
        # Issue #525: avg_hr/max_hr were never backfilled by the old merge_postprocess path even
        # though Record.heartrate is stored - closed as part of unifying with ingest's own
        # computation, which has always set these two. _make_activity's defaults (avg_hr=140,
        # max_hr=160) are deliberately stale here, standing in for pre-merge values a rewritten
        # heartrate stream must overwrite.
        activity = self._bike_activity()
        self._add_records(activity, power=250, heartrate=120)
        Record.objects.filter(activity=activity, t=599).update(heartrate=190)

        recompute_after_merge(activity.id)

        activity.refresh_from_db()
        self.assertEqual(activity.avg_hr, 120)
        self.assertEqual(activity.max_hr, 190)

    def test_runs_the_full_athlete_threshold_ledger_tss_cascade(self):
        activity = self._bike_activity()
        self._add_records(activity, power=220)
        self.assertEqual(activity.tss, 50)  # _make_activity's default, not yet rated

        recompute_after_merge(activity.id)

        activity.refresh_from_db()
        # 220W against a 200W FTP for 10 minutes rates to a non-trivial, non-default TSS.
        self.assertNotEqual(activity.tss, 50)
        self.assertGreater(activity.tss, 0)

    def test_writes_duration_curve_and_best_efforts_for_an_ordinary_activity(self):
        activity = self._bike_activity()
        self._add_records(activity, power=250)

        recompute_after_merge(activity.id)

        self.assertTrue(DurationCurve.objects.filter(activity=activity, metric="power").exists())
        self.assertTrue(BestEffort.objects.filter(athlete=self.athlete, kind="cycling_power").exists())

    def test_skips_duration_curve_and_best_efforts_for_a_multisport_parent(self):
        activity = _make_activity(self.athlete, sport="multisport", start_date=self.start, moving_time=600)
        self._add_records(activity, power=250)

        recompute_after_merge(activity.id)

        self.assertFalse(DurationCurve.objects.filter(activity=activity).exists())
        self.assertFalse(BestEffort.objects.filter(athlete=self.athlete).exists())

    def test_matches_a_same_day_scheduled_workout(self):
        workout = Workout.objects.create(created_by=self.athlete, name="Merged ride", sport="bike")
        scheduled = ScheduledWorkout.objects.create(workout=workout, athlete=self.athlete, date=self.start.date())
        activity = self._bike_activity()
        self._add_records(activity, power=250)

        recompute_after_merge(activity.id)

        scheduled.refresh_from_db()
        activity.refresh_from_db()
        self.assertEqual(scheduled.status, "completed")
        self.assertEqual(activity.workout_id, workout.id)
