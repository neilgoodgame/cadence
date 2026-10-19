from datetime import timedelta

from django.test import TestCase
from rest_framework.exceptions import ValidationError

from accounts.models import User

from ..models import Lap, Record
from ..splits import get_activity_splits, get_custom_activity_splits
from .helpers import _bearer_client, _make_activity


def _add_pace_records(
    activity,
    distance_km: float,
    pace_s_per_km: float,
    step_s: int = 10,
    power: int | None = None,
    altitude_per_km: float | None = None,
):
    """Constant-pace records, optionally climbing at a steady grade - mirrors
    races.tests.test_splits._add_constant_pace_records, duplicated here to avoid a cross-app
    test import."""
    total_s = int(distance_km * pace_s_per_km)
    t = 0
    while t <= total_s:
        km = t / pace_s_per_km
        Record.objects.create(
            activity=activity,
            t=t,
            ts=activity.start_date + timedelta(seconds=t),
            distance_km=round(km, 4),
            heartrate=140,
            power=power,
            altitude=km * altitude_per_km if altitude_per_km is not None else None,
        )
        t += step_s


class ActivitySplitsUnitTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(email="actsplits@example.cc", password="x", name="Athlete")
        self.activity = _make_activity(self.athlete, distance_km=10.0, moving_time=3000)

    def test_laps_mode_has_no_ref_or_delta_fields(self):
        Lap.objects.create(activity=self.activity, index=1, duration=300, distance_km=1.0, avg_hr=140, avg_power=255)
        Lap.objects.create(activity=self.activity, index=2, duration=300, distance_km=1.0, avg_hr=142)

        result = get_activity_splits(self.activity, "laps")
        self.assertTrue(result["has_laps"])
        self.assertNotIn("ref", result)
        self.assertNotIn("ref_pace_s_per_km", result)
        self.assertNotIn("ref_power", result)
        split = result["splits"][0]
        self.assertEqual(split["time_s"], 300)
        self.assertEqual(split["avg_power"], 255)
        self.assertNotIn("delta_vs_ref_s_per_km", split)
        self.assertNotIn("delta_power_w", split)

    def test_single_lap_reports_has_laps_false(self):
        Lap.objects.create(activity=self.activity, index=1, duration=3000, distance_km=10.0)
        result = get_activity_splits(self.activity, "laps")
        self.assertFalse(result["has_laps"])

    def test_fixed_distance_mode_builds_1km_splits(self):
        _add_pace_records(self.activity, distance_km=3.0, pace_s_per_km=300, power=250)
        result = get_activity_splits(self.activity, "1")
        self.assertEqual(len(result["splits"]), 3)
        self.assertEqual(result["splits"][0]["pace_s_per_km"], 300.0)
        self.assertEqual(result["splits"][0]["avg_power"], 250)

    def test_elevation_mode_splits_by_the_activitys_own_sections(self):
        # Flat, then a steep climb, then flat again - same shape as
        # activities.tests.test_sections's fixture.
        def altitude(km: float) -> float:
            if km <= 1.0:
                return 50.0
            if km <= 1.6:
                return 50.0 + (km - 1.0) * 50.0
            return 80.0

        pace_s_per_km = 300
        total_s = int(3.0 * pace_s_per_km)
        for t in range(0, total_s + 1, 10):
            km = t / pace_s_per_km
            Record.objects.create(
                activity=self.activity,
                t=t,
                ts=self.activity.start_date + timedelta(seconds=t),
                distance_km=round(km, 4),
                altitude=altitude(km),
            )

        result = get_activity_splits(self.activity, "elevation")
        self.assertEqual([s["section_type"] for s in result["splits"]], ["flat", "steep_climb", "flat"])
        self.assertNotIn("delta_vs_ref_s_per_km", result["splits"][0])

    def test_elevation_mode_is_empty_when_there_is_no_altitude(self):
        _add_pace_records(self.activity, distance_km=3.0, pace_s_per_km=300)
        result = get_activity_splits(self.activity, "elevation")
        self.assertEqual(result, {"has_laps": False, "halves": None, "splits": []})

    def test_unknown_mode_raises(self):
        with self.assertRaises(ValueError):
            get_activity_splits(self.activity, "weird")


class CustomActivitySplitsTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(email="actcustomsplits@example.cc", password="x", name="Athlete")
        self.activity = _make_activity(self.athlete, distance_km=10.0, moving_time=3000)

    def test_mixed_time_and_distance_steps_are_consumed_sequentially(self):
        # 1s sampling, not the usual 10s step: a "time" step walks the activity's active_t clock,
        # which treats any inter-sample gap over 1s as a pause - a 10s step would make every
        # sample look paused, collapsing active_t to 1/10th of real elapsed time.
        _add_pace_records(self.activity, distance_km=5.0, pace_s_per_km=300, step_s=1, power=250)
        # 1st: exactly 1km by distance. 2nd: exactly 5 minutes (=1km at this pace) by time.
        # 3rd (no length given): the rest of the recording.
        result = get_custom_activity_splits(
            self.activity,
            [
                {"end_type": "distance", "distance_km": 1.0, "label": "Mile-ish"},
                {"end_type": "time", "duration_s": 300},
            ],
        )
        self.assertEqual(len(result["splits"]), 3)  # 2 requested steps + 1 trailing remainder
        self.assertEqual(result["splits"][0]["label"], "Mile-ish")
        self.assertAlmostEqual(result["splits"][0]["end_km"], 1.0, places=2)
        self.assertAlmostEqual(result["splits"][1]["start_km"], 1.0, places=2)
        self.assertAlmostEqual(result["splits"][1]["end_km"], 2.0, places=2)
        self.assertEqual(result["splits"][1]["time_s"], 300)
        self.assertIsNone(result["splits"][2]["label"])
        self.assertAlmostEqual(result["splits"][2]["start_km"], 2.0, places=2)
        self.assertNotIn("delta_vs_ref_s_per_km", result["splits"][0])

    def test_a_mile_split_can_be_expressed_as_1_609_km(self):
        _add_pace_records(self.activity, distance_km=3.0, pace_s_per_km=300)
        result = get_custom_activity_splits(self.activity, [{"end_type": "distance", "distance_km": 1.609}])
        self.assertAlmostEqual(result["splits"][0]["end_km"], 1.609, places=2)

    def test_steps_shorter_than_the_recording_dont_need_to_cover_all_of_it(self):
        _add_pace_records(self.activity, distance_km=5.0, pace_s_per_km=300)
        result = get_custom_activity_splits(self.activity, [{"end_type": "distance", "distance_km": 2.0}])
        self.assertEqual(len(result["splits"]), 2)  # the requested step + the trailing remainder

    def test_steps_longer_than_the_recording_stop_producing_splits(self):
        _add_pace_records(self.activity, distance_km=1.0, pace_s_per_km=300)
        result = get_custom_activity_splits(
            self.activity,
            [{"end_type": "distance", "distance_km": 1.0}, {"end_type": "distance", "distance_km": 5.0}],
        )
        self.assertEqual(len(result["splits"]), 1)  # the 2nd step has no recording left to consume

    def test_empty_steps_list_is_rejected(self):
        with self.assertRaises(ValidationError):
            get_custom_activity_splits(self.activity, [])

    def test_non_list_steps_is_rejected(self):
        with self.assertRaises(ValidationError):
            get_custom_activity_splits(self.activity, None)

    def test_missing_duration_for_a_time_step_is_rejected(self):
        with self.assertRaises(ValidationError):
            get_custom_activity_splits(self.activity, [{"end_type": "time"}])

    def test_zero_distance_for_a_distance_step_is_rejected(self):
        with self.assertRaises(ValidationError):
            get_custom_activity_splits(self.activity, [{"end_type": "distance", "distance_km": 0}])

    def test_unknown_end_type_is_rejected(self):
        with self.assertRaises(ValidationError):
            get_custom_activity_splits(self.activity, [{"end_type": "laps", "duration_s": 60}])


class ActivitySplitsViewTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(email="actsplitsview@example.cc", password="x", name="Athlete")
        self.outsider = User.objects.create_user(email="actsplitsview-out@example.cc", password="x", name="Outsider")
        self.activity = _make_activity(self.athlete, distance_km=10.0, moving_time=3000)

    def test_defaults_to_laps_mode(self):
        response = _bearer_client(self.athlete).get(f"/v1/activities/{self.activity.id}/splits")
        self.assertEqual(response.status_code, 200)
        self.assertIn("has_laps", response.json())

    def test_rejects_unknown_mode(self):
        response = _bearer_client(self.athlete).get(f"/v1/activities/{self.activity.id}/splits", {"mode": "weird"})
        self.assertEqual(response.status_code, 400)

    def test_outsider_cannot_read(self):
        response = _bearer_client(self.outsider).get(f"/v1/activities/{self.activity.id}/splits")
        self.assertEqual(response.status_code, 403)

    def test_custom_splits_post_builds_splits_from_steps(self):
        _add_pace_records(self.activity, distance_km=3.0, pace_s_per_km=300)
        response = _bearer_client(self.athlete).post(
            f"/v1/activities/{self.activity.id}/splits",
            {"steps": [{"end_type": "distance", "distance_km": 1.0}]},
            format="json",
        )
        self.assertEqual(response.status_code, 200)
        self.assertEqual(len(response.json()["splits"]), 2)

    def test_custom_splits_post_rejects_malformed_steps(self):
        response = _bearer_client(self.athlete).post(
            f"/v1/activities/{self.activity.id}/splits", {"steps": [{"end_type": "time"}]}, format="json"
        )
        self.assertEqual(response.status_code, 400)

    def test_custom_splits_post_requires_read_access(self):
        response = _bearer_client(self.outsider).post(
            f"/v1/activities/{self.activity.id}/splits",
            {"steps": [{"end_type": "distance", "distance_km": 1.0}]},
            format="json",
        )
        self.assertEqual(response.status_code, 403)
