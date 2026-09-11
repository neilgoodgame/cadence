from datetime import timedelta

from django.test import TestCase

from accounts.models import User
from workouts.models import Workout, WorkoutStep

from ..models import Lap, Record
from .helpers import _bearer_client, _delegated_client, _make_activity


class LapListViewTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(email="athlete@example.cc", password="x", name="Athlete")
        self.outsider = User.objects.create_user(email="outsider@example.cc", password="x", name="Outsider")

    def test_lists_laps_in_order(self):
        activity = _make_activity(self.athlete)
        Lap.objects.create(activity=activity, index=2, duration=300, distance_km=1.0)
        Lap.objects.create(activity=activity, index=1, duration=290, distance_km=1.0)

        response = _bearer_client(self.athlete).get(f"/v1/activities/{activity.id}/laps")
        self.assertEqual(response.status_code, 200)
        indexes = [lap["index"] for lap in response.json()["data"]]
        self.assertEqual(indexes, [1, 2])

    def test_outsider_forbidden(self):
        activity = _make_activity(self.athlete)
        client = _delegated_client(self.outsider, self.athlete, scopes=["activities:read"])
        response = client.get(f"/v1/activities/{activity.id}/laps")
        self.assertEqual(response.status_code, 403)

    def test_includes_step_context_for_a_derived_lap(self):
        activity = _make_activity(self.athlete)
        workout = Workout.objects.create(created_by=self.athlete, name="W", sport="run")
        step = WorkoutStep.objects.create(
            workout=workout,
            order=0,
            kind="block",
            end_type="time",
            duration=300,
            target_type="power",
            target_low=100,
            target_high=110,
        )
        Lap.objects.create(activity=activity, index=1, duration=300, distance_km=1.0, workout_step=step, repeat_index=3)

        response = _bearer_client(self.athlete).get(f"/v1/activities/{activity.id}/laps")

        lap = response.json()["data"][0]
        self.assertEqual(lap["workout_step_id"], step.id)
        self.assertEqual(lap["repeat_index"], 3)
        self.assertEqual(lap["step_kind"], "block")
        self.assertEqual(lap["step_target_type"], "power")
        self.assertEqual(lap["step_target_low"], 100)
        self.assertEqual(lap["step_target_high"], 110)
        self.assertEqual(lap["step_power_unit"], "pct_ftp")
        # The step's own planned duration - distinct from the lap's own `duration` (300, matching
        # here since the fixture's lap was recorded exactly as planned), used by the frontend to
        # tell two identically-targeted-but-differently-timed steps apart (see
        # lapPresentation.ts::summarizeSteps).
        self.assertEqual(lap["step_duration"], 300)
        self.assertIsNone(lap["step_distance"])

    def test_step_context_is_null_for_an_unlinked_lap(self):
        activity = _make_activity(self.athlete)
        Lap.objects.create(activity=activity, index=1, duration=300, distance_km=1.0)

        response = _bearer_client(self.athlete).get(f"/v1/activities/{activity.id}/laps")

        lap = response.json()["data"][0]
        self.assertIsNone(lap["workout_step_id"])
        self.assertIsNone(lap["step_kind"])
        self.assertIsNone(lap["step_power_unit"])
        self.assertIsNone(lap["step_duration"])
        self.assertIsNone(lap["step_distance"])


def _make_gorby_workout(athlete: User) -> Workout:
    workout = Workout.objects.create(created_by=athlete, name="The Gorby", sport="bike")
    WorkoutStep.objects.create(
        workout=workout,
        order=0,
        kind="warmup",
        end_type="time",
        duration=600,
        target_type="power",
        target_low=50,
        target_high=70,
    )
    group = WorkoutStep.objects.create(workout=workout, order=1, kind="repeat", repeat=5)
    WorkoutStep.objects.create(
        workout=workout,
        parent=group,
        order=0,
        kind="block",
        end_type="time",
        duration=300,
        target_type="power",
        target_low=110,
        target_high=110,
    )
    WorkoutStep.objects.create(
        workout=workout,
        parent=group,
        order=1,
        kind="rec",
        end_type="time",
        duration=300,
        target_type="power",
        target_low=50,
        target_high=55,
    )
    return workout


class RegenerateActivityLapsViewTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(email="regen-laps@example.cc", password="x", name="Athlete")
        self.outsider = User.objects.create_user(email="regen-laps-outsider@example.cc", password="x", name="Outsider")

    def test_regenerates_laps_from_the_matched_workout(self):
        workout = _make_gorby_workout(self.athlete)
        activity = _make_activity(self.athlete, sport="bike", workout=workout)
        for t in range(3601):
            Record.objects.create(activity=activity, t=t, ts=activity.start_date + timedelta(seconds=t), power=200)
        Lap.objects.create(activity=activity, index=1, duration=3600, distance_km=36.0, avg_power=200)

        response = _bearer_client(self.athlete).post(f"/v1/activities/{activity.id}/regenerate-laps")

        self.assertEqual(response.status_code, 200)
        self.assertEqual(len(response.json()["data"]), 11)
        self.assertEqual(activity.laps.count(), 11)

    def test_rejects_an_activity_with_no_matched_workout(self):
        activity = _make_activity(self.athlete, sport="bike")

        response = _bearer_client(self.athlete).post(f"/v1/activities/{activity.id}/regenerate-laps")

        self.assertEqual(response.status_code, 400)

    def test_rejects_when_derivation_is_not_possible(self):
        workout = _make_gorby_workout(self.athlete)
        activity = _make_activity(self.athlete, sport="bike", workout=workout)  # no records seeded

        response = _bearer_client(self.athlete).post(f"/v1/activities/{activity.id}/regenerate-laps")

        self.assertEqual(response.status_code, 400)

    def test_outsider_forbidden(self):
        workout = _make_gorby_workout(self.athlete)
        activity = _make_activity(self.athlete, sport="bike", workout=workout)
        client = _delegated_client(self.outsider, self.athlete, scopes=["activities:write"])

        response = client.post(f"/v1/activities/{activity.id}/regenerate-laps")

        self.assertEqual(response.status_code, 403)
