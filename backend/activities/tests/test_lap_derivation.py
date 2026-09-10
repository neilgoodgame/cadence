from datetime import timedelta

from django.test import TestCase

from accounts.models import User
from workouts.models import Workout, WorkoutStep

from ..lap_derivation import derive_laps_from_workout, replace_laps_with_derived
from ..models import Lap, Record
from .helpers import _make_activity


def _make_gorby_workout(athlete: User) -> Workout:
    """warmup 600s@60%, then 5x[work 300s@110%, rest 300s@52.5%] - the real workout this
    feature was designed against (see the session's "Gorby" investigation)."""
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


def _phase_power(t: int) -> int:
    if t < 600:
        return 150  # warmup
    rep_t = (t - 600) % 600
    return 280 if rep_t < 300 else 140  # work / rest


def _seed_records(activity, total_seconds: int) -> None:
    for t in range(total_seconds):
        Record.objects.create(
            activity=activity,
            t=t,
            ts=activity.start_date + timedelta(seconds=t),
            power=_phase_power(t),
            heartrate=130 + (t % 20),
            distance_km=t * 0.01,
        )


class DeriveLapsFromWorkoutTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(email="lap-derivation@example.cc", password="x", name="Athlete")

    def test_derives_11_laps_matching_the_gorbys_real_structure(self):
        activity = _make_activity(self.athlete, sport="bike")
        workout = _make_gorby_workout(self.athlete)
        # +1: adjacent segments share their boundary sample (it closes one, opens the next), so
        # a clean run through all 11 planned segments needs one more sample than their summed
        # planned duration (3600s).
        _seed_records(activity, 3601)

        laps = derive_laps_from_workout(activity, workout)

        self.assertEqual(len(laps), 11)
        self.assertEqual([lap.duration for lap in laps], [600] + [300] * 10)
        self.assertEqual([lap.index for lap in laps], list(range(1, 12)))
        # warmup
        self.assertEqual(laps[0].workout_step.kind, "warmup")
        self.assertIsNone(laps[0].repeat_index)
        self.assertAlmostEqual(laps[0].avg_power, 150, delta=1)
        # rep 1 work/rest
        self.assertEqual(laps[1].workout_step.kind, "block")
        self.assertEqual(laps[1].repeat_index, 1)
        self.assertAlmostEqual(laps[1].avg_power, 280, delta=1)
        self.assertEqual(laps[2].workout_step.kind, "rec")
        self.assertEqual(laps[2].repeat_index, 1)
        self.assertAlmostEqual(laps[2].avg_power, 140, delta=1)
        # rep 5 (last) work/rest, and every "block" lap points at the same WorkoutStep row
        self.assertEqual(laps[9].repeat_index, 5)
        block_step_ids = {lap.workout_step_id for lap in laps if lap.workout_step.kind == "block"}
        self.assertEqual(len(block_step_ids), 1)

    def test_trailing_records_beyond_the_plan_become_one_unlinked_lap(self):
        # Matches the real Gorby ride investigated during planning: 12 device laps for an
        # 11-step workout, the extra one a short "stop recording" tail.
        activity = _make_activity(self.athlete, sport="bike")
        workout = _make_gorby_workout(self.athlete)
        _seed_records(activity, 3637)  # 3601 to cleanly complete the plan + 36s trailing

        laps = derive_laps_from_workout(activity, workout)

        self.assertEqual(len(laps), 12)
        self.assertIsNone(laps[-1].workout_step)
        self.assertIsNone(laps[-1].repeat_index)
        self.assertEqual(laps[-1].duration, 35)  # last record's t (3636) - first (3601)

    def test_activity_shorter_than_the_plan_produces_no_lap_for_unreached_steps(self):
        activity = _make_activity(self.athlete, sport="bike")
        workout = _make_gorby_workout(self.athlete)
        _seed_records(activity, 900)  # warmup + first work interval only, athlete stopped early

        laps = derive_laps_from_workout(activity, workout)

        self.assertEqual(len(laps), 2)
        self.assertEqual(laps[0].workout_step.kind, "warmup")
        self.assertEqual(laps[1].workout_step.kind, "block")
        self.assertEqual(laps[1].repeat_index, 1)

    def test_manual_ended_step_anywhere_in_the_plan_returns_none(self):
        activity = _make_activity(self.athlete, sport="bike")
        workout = Workout.objects.create(created_by=self.athlete, name="Has a manual step", sport="bike")
        WorkoutStep.objects.create(
            workout=workout,
            order=0,
            kind="block",
            end_type="manual",
            target_type="power",
            target_low=100,
            target_high=100,
        )
        _seed_records(activity, 600)

        self.assertIsNone(derive_laps_from_workout(activity, workout))

    def test_no_records_returns_none(self):
        activity = _make_activity(self.athlete, sport="bike")
        workout = _make_gorby_workout(self.athlete)

        self.assertIsNone(derive_laps_from_workout(activity, workout))


class ReplaceLapsWithDerivedTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(email="lap-replace@example.cc", password="x", name="Athlete")

    def test_replaces_existing_laps_with_derived_ones(self):
        activity = _make_activity(self.athlete, sport="bike")
        workout = _make_gorby_workout(self.athlete)
        _seed_records(activity, 3600)
        Lap.objects.create(activity=activity, index=1, duration=3600, distance_km=36.0, avg_power=200)

        result = replace_laps_with_derived(activity, workout)

        self.assertTrue(result)
        self.assertEqual(activity.laps.count(), 11)
        self.assertEqual(activity.laps.first().workout_step.kind, "warmup")

    def test_leaves_existing_laps_untouched_when_derivation_is_not_possible(self):
        activity = _make_activity(self.athlete, sport="bike")
        workout = _make_gorby_workout(self.athlete)
        Lap.objects.create(activity=activity, index=1, duration=3600, distance_km=36.0, avg_power=200)

        result = replace_laps_with_derived(activity, workout)  # no records seeded

        self.assertFalse(result)
        self.assertEqual(activity.laps.count(), 1)
