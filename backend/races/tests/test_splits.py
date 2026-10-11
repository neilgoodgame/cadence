from datetime import UTC, datetime, timedelta

from django.test import TestCase

from accounts.models import User
from activities.models import Lap, Record
from activities.tests.helpers import _make_activity
from races.models import Race
from races.splits import get_splits


def _add_constant_pace_records(activity, distance_km: float, pace_s_per_km: float, step_s: int = 10):
    total_s = int(distance_km * pace_s_per_km)
    t = 0
    start = activity.start_date
    while t <= total_s:
        Record.objects.create(
            activity=activity,
            t=t,
            ts=start + timedelta(seconds=t),
            distance_km=round(t / pace_s_per_km, 4),
            heartrate=140,
        )
        t += step_s


class LapSplitsTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(email="athlete@example.cc", password="x", name="Athlete")
        self.activity = _make_activity(
            self.athlete, start_date=datetime(2026, 10, 12, 8, 0, tzinfo=UTC), distance_km=10.0, moving_time=3000
        )
        self.race = Race.objects.create(
            athlete=self.athlete,
            name="10k",
            date=self.activity.start_date.date(),
            activity=self.activity,
            distance_km=10.0,
        )

    def test_multiple_laps_report_has_laps_true(self):
        Lap.objects.create(activity=self.activity, index=1, duration=300, distance_km=1.0, avg_hr=140)
        Lap.objects.create(activity=self.activity, index=2, duration=300, distance_km=1.0, avg_hr=142)

        result = get_splits(self.race, "laps")
        self.assertTrue(result["has_laps"])
        self.assertEqual(len(result["splits"]), 2)
        self.assertEqual(result["splits"][0]["time_s"], 300)
        self.assertEqual(result["splits"][1]["start_km"], 1.0)

    def test_single_lap_reports_has_laps_false(self):
        Lap.objects.create(activity=self.activity, index=1, duration=3000, distance_km=10.0, avg_hr=140)

        result = get_splits(self.race, "laps")
        self.assertFalse(result["has_laps"])

    def test_no_laps_at_all_reports_has_laps_false(self):
        result = get_splits(self.race, "laps")
        self.assertFalse(result["has_laps"])
        self.assertEqual(result["splits"], [])


class FixedDistanceSplitsTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(email="athlete@example.cc", password="x", name="Athlete")
        # 5.5km at a steady 5:00/km (300s/km) pace.
        self.activity = _make_activity(
            self.athlete, start_date=datetime(2026, 10, 12, 8, 0, tzinfo=UTC), distance_km=5.5, moving_time=1650
        )
        _add_constant_pace_records(self.activity, distance_km=5.5, pace_s_per_km=300)
        self.race = Race.objects.create(
            athlete=self.athlete,
            name="5.5k time trial",
            date=self.activity.start_date.date(),
            activity=self.activity,
            distance_km=5.5,
            goal_time=timedelta(minutes=27, seconds=30),  # exactly 300s/km goal pace
        )

    def test_1km_splits_cover_the_whole_distance_with_a_partial_last_split(self):
        result = get_splits(self.race, "1")
        self.assertEqual(len(result["splits"]), 6)  # 5 full km + one 0.5km partial
        last = result["splits"][-1]
        self.assertAlmostEqual(last["end_km"] - last["start_km"], 0.5, places=2)
        self.assertEqual(last["label"], "Finish")

    def test_splits_pace_matches_goal_reference(self):
        result = get_splits(self.race, "1")
        self.assertEqual(result["ref"], "goal")
        first = result["splits"][0]
        self.assertAlmostEqual(first["pace_s_per_km"], 300, delta=1)
        self.assertAlmostEqual(first["delta_vs_ref_s_per_km"], 0, delta=1)

    def test_5km_splits_produce_two_splits(self):
        result = get_splits(self.race, "5")
        self.assertEqual(len(result["splits"]), 2)
        self.assertAlmostEqual(result["splits"][0]["end_km"], 5.0, places=2)

    def test_no_linked_activity_returns_empty_splits(self):
        race = Race.objects.create(athlete=self.athlete, name="No activity race", date=self.activity.start_date.date())
        result = get_splits(race, "1")
        self.assertEqual(result["splits"], [])
        self.assertFalse(result["has_laps"])
