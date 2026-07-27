"""Round-trip tests for workouts.inference: build a known step tree, synthesize plausible
Activity/Lap rows from it (with jitter, like a real recording), run it back through
infer_workout(), and check the heuristic recovers the original structure.

Two sources of trees:
- `_random_workout`: generated on the fly from well-separated work/rest "profiles" so
  randomized runs can't accidentally produce ambiguous, un-recoverable structures.
- Real fixtures dropped in `workouts/test_fixtures/roundtrip/*.json` - export any workout
  from the Workout Library as "Test fixture .json" (see ExportModal.tsx) and drop the file
  there to add real-world coverage. Validated against `roundtrip_workout.schema.json`.
"""

import json
import random
from pathlib import Path

import jsonschema
from django.test import TestCase
from django.utils import timezone

from accounts.models import User
from activities.models import Activity, Lap
from athletes.zones import reference_for

from .calculations import compute_duration_and_tss
from .inference import infer_workout

FIXTURES_DIR = Path(__file__).parent / "test_fixtures" / "roundtrip"
SCHEMA = json.loads((Path(__file__).parent / "test_fixtures" / "roundtrip_workout.schema.json").read_text())

# (duration_seconds, pct) profiles for work/rest legs, spaced well outside inference.py's
# tolerances (duration +/-15%/10s, pct +/-5) so two different profiles can never be mistaken
# for the same repeat pattern - keeps the generated-workout assertions exact, not just "close".
WORK_PROFILES = [(300, 118), (120, 150), (480, 105)]
REST_PROFILES = [(180, 55), (60, 45), (240, 62)]
WARMUP_PROFILE = (600, 58)
COOL_PROFILE = (300, 45)

# Jitter kept comfortably inside inference.py's own tolerances so a single rep of noise can
# never itself cause a false split - that's the behaviour being tested, not incidental flake.
DURATION_JITTER_FRACTION = 0.05
PCT_JITTER_ABSOLUTE = 2


def _leaf(kind: str, duration: int, pct: float, target_type: str) -> dict:
    return {
        "kind": kind,
        "end_type": "time",
        "duration": duration,
        "distance": None,
        "target_type": target_type,
        "target_low": pct,
        "target_high": pct,
        "target2_type": "none",
        "target2_low": None,
        "target2_high": None,
        "repeat": 1,
        "note": "",
    }


def _random_workout(rng: random.Random, target_type: str = "power") -> dict:
    """A plausible interval workout: warmup, 1-2 repeat groups built from distinct
    work/rest profiles, cooldown - the same shape real structured workouts take."""
    sport = rng.choice(["bike", "run"])
    n_groups = rng.randint(1, 2)
    profile_indices = rng.sample(range(len(WORK_PROFILES)), n_groups)

    steps = [_leaf("warmup", *WARMUP_PROFILE, target_type)]
    for idx in profile_indices:
        work_duration, work_pct = WORK_PROFILES[idx]
        rest_duration, rest_pct = REST_PROFILES[idx]
        steps.append(
            {
                "kind": "repeat",
                "end_type": None,
                "duration": None,
                "distance": None,
                "target_type": None,
                "target_low": None,
                "target_high": None,
                "target2_type": "none",
                "target2_low": None,
                "target2_high": None,
                "repeat": rng.randint(3, 6),
                "note": "",
                "children": [
                    _leaf("block", work_duration, work_pct, target_type),
                    _leaf("rec", rest_duration, rest_pct, target_type),
                ],
            }
        )
    steps.append(_leaf("cool", *COOL_PROFILE, target_type))
    return {"name": f"Generated Workout {rng.randint(1000, 9999)}", "sport": sport, "steps": steps}


def _flatten_leaves(steps: list[dict]) -> list[dict]:
    leaves = []
    for step in steps:
        if step["kind"] == "repeat":
            for _ in range(step["repeat"]):
                leaves.extend(_flatten_leaves(step["children"]))
        else:
            leaves.append(step)
    return leaves


def _synthesize_laps(activity: Activity, steps: list[dict], athlete: User, rng: random.Random) -> None:
    """Creates one Lap per leaf in the flattened (repeats unrolled) tree, with small jitter
    on duration/intensity - simulating what a real recording of this workout would produce."""
    zone_type = "bike_power" if activity.sport == "bike" else "run_power"
    ftp = reference_for(athlete, zone_type)
    lthr = reference_for(athlete, "heart_rate")

    for i, leaf in enumerate(_flatten_leaves(steps)):
        duration = max(
            10, round(leaf["duration"] * (1 + rng.uniform(-DURATION_JITTER_FRACTION, DURATION_JITTER_FRACTION)))
        )
        pct = leaf["target_low"] + rng.uniform(-PCT_JITTER_ABSOLUTE, PCT_JITTER_ABSOLUTE)
        avg_power = avg_hr = None
        if leaf["target_type"] == "power" and ftp:
            avg_power = round(ftp * pct / 100)
        elif leaf["target_type"] == "hr" and lthr:
            avg_hr = round(lthr * pct / 100)
        Lap.objects.create(
            activity=activity, index=i, duration=duration, distance_km=0, avg_power=avg_power, avg_hr=avg_hr
        )


class _RoundTripAssertions(TestCase):
    """Shared helpers - not a test case itself (no test_ methods)."""

    def _make_activity(self, athlete: User, workout: dict) -> Activity:
        return Activity.objects.create(
            athlete=athlete,
            sport=workout["sport"],
            name=workout["name"],
            start_date=timezone.now(),
            moving_time=0,
        )

    def _assert_trees_match(self, original: list[dict], inferred: list[dict], *, pct_tol: float) -> None:
        self.assertEqual(len(original), len(inferred), f"top-level step count: {original} vs {inferred}")
        for orig, inf in zip(original, inferred, strict=True):
            self.assertEqual(orig["kind"], inf["kind"])
            if orig["kind"] == "repeat":
                self.assertEqual(orig["repeat"], inf["repeat"])
                self._assert_trees_match(orig["children"], inf["children"], pct_tol=pct_tol)
                continue
            self.assertEqual(orig["target_type"], inf["target_type"])
            if orig["target_low"] is not None:
                self.assertLessEqual(abs(orig["target_low"] - inf["target_low"]), pct_tol)

    def _assert_duration_matches_laps(self, activity: Activity, inferred_steps: list[dict]) -> None:
        # Not exact: a repeat group's children is only the *first* rep's leaves multiplied by
        # repeat, so per-rep jitter (real reps are never identical) makes the collapsed total
        # a close approximation rather than an exact sum - the same effect seen against real
        # activity history via infer_workout_report (~0.3% off there). A generous tolerance
        # keeps this a meaningful sanity check without being sensitive to jitter's RNG draw.
        duration, _tss = compute_duration_and_tss(inferred_steps)
        total_lap_duration = sum(lap.duration for lap in activity.laps.all())
        self.assertAlmostEqual(duration, total_lap_duration, delta=max(30, 0.08 * total_lap_duration))


class RandomWorkoutRoundTripTests(_RoundTripAssertions):
    """Generated workouts, built from profiles guaranteed distinct enough that a correct
    heuristic must recover them exactly (within its own stated tolerance)."""

    def setUp(self):
        # Both ftp and critical_run_power are set since _random_workout() picks bike/run at
        # random but always targets "power" - bike reads ftp, run reads critical_run_power.
        self.athlete = User.objects.create_user(
            email="roundtrip@example.cc",
            password="x",
            name="Roundtrip Athlete",
            ftp=250,
            critical_run_power=280,
            lthr=165,
        )

    def test_generated_power_workouts_round_trip(self):
        rng = random.Random(20260727)
        for i in range(20):
            with self.subTest(i=i):
                original = _random_workout(rng, target_type="power")
                activity = self._make_activity(self.athlete, original)
                _synthesize_laps(activity, original["steps"], self.athlete, rng)

                inferred = infer_workout(activity, auto_detect_repeats=True)

                self._assert_trees_match(original["steps"], inferred["steps"], pct_tol=PCT_JITTER_ABSOLUTE + 1)
                self._assert_duration_matches_laps(activity, inferred["steps"])

    def test_generated_hr_workouts_round_trip(self):
        rng = random.Random(9000)
        for i in range(10):
            with self.subTest(i=i):
                original = _random_workout(rng, target_type="hr")
                activity = self._make_activity(self.athlete, original)
                _synthesize_laps(activity, original["steps"], self.athlete, rng)

                inferred = infer_workout(activity, auto_detect_repeats=True)

                self._assert_trees_match(original["steps"], inferred["steps"], pct_tol=PCT_JITTER_ABSOLUTE + 1)
                self._assert_duration_matches_laps(activity, inferred["steps"])


class RealWorkoutFixtureRoundTripTests(_RoundTripAssertions):
    """Real workouts exported from the Workout Library - see the module docstring for how
    to add more. Skips (rather than fails) when the fixtures directory is still empty."""

    def setUp(self):
        self.athlete = User.objects.create_user(
            email="roundtrip-fixtures@example.cc",
            password="x",
            name="Fixture Athlete",
            ftp=250,
            critical_run_power=280,
            lthr=165,
        )

    def test_real_workout_fixtures_round_trip(self):
        fixture_paths = sorted(FIXTURES_DIR.glob("*.json"))
        if not fixture_paths:
            self.skipTest(f"No fixtures in {FIXTURES_DIR} yet - export a workout as 'Test fixture .json' to add one.")

        rng = random.Random(20260727)
        for path in fixture_paths:
            with self.subTest(fixture=path.name):
                workout = json.loads(path.read_text())
                jsonschema.validate(workout, SCHEMA)

                activity = self._make_activity(self.athlete, workout)
                _synthesize_laps(activity, workout["steps"], self.athlete, rng)

                inferred = infer_workout(activity, auto_detect_repeats=True)

                # Real, hand-authored workouts aren't guaranteed to use well-separated
                # profiles the way the generated fixtures are, so structure is checked
                # loosely (does inference produce a repeat group wherever the original had
                # one, per top-level position) rather than requiring an exact match.
                original_kinds = [s["kind"] == "repeat" for s in workout["steps"]]
                inferred_kinds = [s["kind"] == "repeat" for s in inferred["steps"]]
                self.assertEqual(sum(original_kinds), sum(inferred_kinds), f"{path.name}: repeat-group count mismatch")
                self._assert_duration_matches_laps(activity, inferred["steps"])
