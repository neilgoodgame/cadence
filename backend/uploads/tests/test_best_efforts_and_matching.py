from datetime import UTC, date, datetime

from django.test import TestCase

from accounts.models import User
from activities.models import Activity, ActivityTag, BestEffort
from scheduling.models import ScheduledWorkout
from workouts.models import Workout

from ..processing import attempt_workout_match, update_best_efforts


class BestEffortUpsertTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(email="be@example.cc", password="x", name="BE Athlete", ftp=1)

    def _activity(self, suffix):
        return Activity.objects.create(
            athlete=self.athlete,
            sport="bike",
            name=f"Ride {suffix}",
            start_date=datetime(2026, 6, 10, 7, 0, tzinfo=UTC),
        )

    def test_improves_then_holds_then_improves_again(self):
        a1 = self._activity("1")
        update_best_efforts(a1, self.athlete, [200] * 60, [])
        effort = BestEffort.objects.get(athlete=self.athlete, kind="cycling_power", window="1min")
        self.assertEqual(effort.value, 200.0)
        self.assertEqual(effort.activity_id, a1.id)

        a2 = self._activity("2")
        update_best_efforts(a2, self.athlete, [150] * 60, [])
        effort.refresh_from_db()
        self.assertEqual(effort.value, 200.0)
        self.assertEqual(effort.activity_id, a1.id)

        a3 = self._activity("3")
        update_best_efforts(a3, self.athlete, [250] * 60, [])
        effort.refresh_from_db()
        self.assertEqual(effort.value, 250.0)
        self.assertEqual(effort.activity_id, a3.id)


class WorkoutMatchingTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(email="wm@example.cc", password="x", name="WM Athlete")

    def test_links_and_tags_matching_scheduled_workout(self):
        workout = Workout.objects.create(created_by=self.athlete, name="Tempo run", sport="run")
        scheduled = ScheduledWorkout.objects.create(workout=workout, athlete=self.athlete, date=date(2026, 6, 11))

        activity = Activity.objects.create(
            athlete=self.athlete,
            sport="run",
            name="Morning run",
            start_date=datetime(2026, 6, 11, 6, 30, tzinfo=UTC),
        )
        attempt_workout_match(activity, self.athlete)

        scheduled.refresh_from_db()
        activity.refresh_from_db()
        self.assertEqual(scheduled.status, "completed")
        self.assertEqual(scheduled.activity_id, activity.id)
        self.assertEqual(activity.workout_id, workout.id)
        self.assertTrue(
            ActivityTag.objects.filter(activity=activity, tag__name="Auto-matched", tag__origin="auto").exists()
        )

    def test_does_not_match_different_sport(self):
        workout = Workout.objects.create(created_by=self.athlete, name="Tempo run", sport="run")
        scheduled = ScheduledWorkout.objects.create(workout=workout, athlete=self.athlete, date=date(2026, 6, 12))

        activity = Activity.objects.create(
            athlete=self.athlete,
            sport="bike",
            name="Easy ride",
            start_date=datetime(2026, 6, 12, 6, 30, tzinfo=UTC),
        )
        attempt_workout_match(activity, self.athlete)

        scheduled.refresh_from_db()
        self.assertEqual(scheduled.status, "planned")
        self.assertIsNone(scheduled.activity_id)
