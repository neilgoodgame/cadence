from datetime import UTC, datetime, timedelta

from django.test import TestCase

from accounts.models import User
from workouts.models import Workout, WorkoutStep
from workouts.tests import _make_gorby_workout, _seed_matching_records

from ..models import Record
from .helpers import _bearer_client, _make_activity


class ActivityWorkoutMatchCandidatesViewTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(email="candidates@example.cc", password="x", name="Athlete")
        self.outsider = User.objects.create_user(email="candidates-outsider@example.cc", password="x", name="Outsider")

    def test_ranks_the_athletes_workout_library_by_correlation(self):
        matching = _make_gorby_workout(self.athlete)
        matching.duration = 3600
        matching.save(update_fields=["duration"])
        other = Workout.objects.create(created_by=self.athlete, name="Recovery ride", sport="bike", duration=3600)
        WorkoutStep.objects.create(
            workout=other,
            order=0,
            kind="block",
            end_type="time",
            duration=3600,
            target_type="power",
            target_low=60,
            target_high=65,
        )
        activity = _make_activity(
            self.athlete, sport="bike", start_date=datetime(2026, 7, 1, 6, 0, tzinfo=UTC), moving_time=3600
        )
        _seed_matching_records(activity, 3601, ftp=250)

        response = _bearer_client(self.athlete).get(f"/v1/activities/{activity.id}/workout-match-candidates")

        self.assertEqual(response.status_code, 200)
        data = response.json()["data"]
        self.assertEqual(data[0]["workoutId"], matching.id)
        self.assertGreater(data[0]["correlation"], 0.99)

    def test_different_sport_workouts_are_not_candidates(self):
        run_workout = Workout.objects.create(created_by=self.athlete, name="Tempo run", sport="run", duration=1800)
        activity = _make_activity(
            self.athlete, sport="bike", start_date=datetime(2026, 7, 2, 6, 0, tzinfo=UTC), moving_time=1800
        )

        response = _bearer_client(self.athlete).get(f"/v1/activities/{activity.id}/workout-match-candidates")

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["data"], [])
        self.assertNotIn(run_workout.id, [c["workoutId"] for c in response.json()["data"]])

    def test_widening_tolerance_includes_a_candidate_outside_the_default_window(self):
        """Regression coverage for a real case found live: a distance-based activity's actual
        moving time can legitimately fall well outside a fixed-duration workout's planned
        duration, well past the default 60s tolerance - toleranceSeconds lets a caller widen the
        pre-filter per-request to investigate that without changing the default."""
        matching = Workout.objects.create(created_by=self.athlete, name="Matching", sport="bike", duration=3400)
        WorkoutStep.objects.create(
            workout=matching,
            order=0,
            kind="block",
            end_type="time",
            duration=1700,
            target_type="power",
            target_low=60,
            target_high=60,
        )
        WorkoutStep.objects.create(
            workout=matching,
            order=1,
            kind="block",
            end_type="time",
            duration=1700,
            target_type="power",
            target_low=110,
            target_high=110,
        )
        start = datetime(2026, 7, 3, 6, 0, tzinfo=UTC)
        activity = _make_activity(self.athlete, sport="bike", start_date=start, moving_time=3600)
        for t in range(3401):
            Record.objects.create(
                activity=activity, t=t, ts=start + timedelta(seconds=t), power=150 if t < 1700 else 280
            )
        client = _bearer_client(self.athlete)

        default_response = client.get(f"/v1/activities/{activity.id}/workout-match-candidates")
        self.assertEqual(default_response.json()["data"], [])

        widened_response = client.get(
            f"/v1/activities/{activity.id}/workout-match-candidates", {"toleranceSeconds": 250}
        )
        data = widened_response.json()["data"]
        self.assertEqual(len(data), 1)
        self.assertEqual(data[0]["workoutId"], matching.id)

    def test_rejects_a_negative_tolerance(self):
        activity = _make_activity(self.athlete, sport="bike")

        response = _bearer_client(self.athlete).get(
            f"/v1/activities/{activity.id}/workout-match-candidates", {"toleranceSeconds": -1}
        )

        self.assertEqual(response.status_code, 400)

    def test_outsider_forbidden(self):
        activity = _make_activity(self.athlete, sport="bike")

        response = _bearer_client(self.outsider).get(f"/v1/activities/{activity.id}/workout-match-candidates")

        self.assertEqual(response.status_code, 403)
