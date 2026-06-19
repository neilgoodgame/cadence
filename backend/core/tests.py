from datetime import date, timedelta

from django.test import Client, TestCase

from accounts.models import User
from scheduling.models import ScheduledWorkout
from workouts.models import Workout

from .derived import compute_compliance, compute_flags


class HealthcheckTests(TestCase):
    def test_healthz_returns_ok(self):
        response = Client().get("/healthz")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json(), {"status": "ok"})


class DerivedMetricsTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(email="athlete@example.cc", password="x", name="Athlete")
        self.workout = Workout.objects.create(created_by=self.athlete, name="Easy ride", sport="bike")
        self.today = date(2026, 1, 28)

    def _schedule(self, day, status):
        return ScheduledWorkout.objects.create(
            workout=self.workout, athlete=self.athlete, date=day, status=status
        )

    def test_compliance_with_no_scheduled_workouts_is_zero(self):
        self.assertEqual(compute_compliance(self.athlete.id, as_of=self.today), 0.0)

    def test_compliance_is_completed_over_total_in_window(self):
        self._schedule(self.today - timedelta(days=1), "completed")
        self._schedule(self.today - timedelta(days=2), "completed")
        self._schedule(self.today - timedelta(days=3), "missed")
        self._schedule(self.today - timedelta(days=4), "planned")
        self.assertEqual(compute_compliance(self.athlete.id, as_of=self.today), 0.5)

    def test_compliance_ignores_workouts_outside_window(self):
        self._schedule(self.today - timedelta(days=1), "completed")
        self._schedule(self.today - timedelta(days=400), "missed")
        self.assertEqual(compute_compliance(self.athlete.id, as_of=self.today), 1.0)

    def test_flags_counts_overdue_still_planned_workouts(self):
        self._schedule(self.today - timedelta(days=1), "planned")
        self._schedule(self.today - timedelta(days=2), "completed")
        self._schedule(self.today, "planned")  # today, not yet overdue
        self.assertEqual(compute_flags(self.athlete.id, as_of=self.today), 1)

    def test_flags_ignores_old_overdue_workouts_outside_window(self):
        self._schedule(self.today - timedelta(days=400), "planned")
        self.assertEqual(compute_flags(self.athlete.id, as_of=self.today), 0)
