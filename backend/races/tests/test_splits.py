from datetime import UTC, datetime, timedelta

from django.test import SimpleTestCase, TestCase

from accounts.models import User
from activities.models import Lap, Record
from activities.tests.helpers import _make_activity
from courses.models import Course
from courses.sections import build_elevation_series
from races.models import Race
from races.splits import _gap_pace_s_per_km, get_splits


class GradeAdjustedPaceTests(SimpleTestCase):
    """Golden values for the Minetti et al. (2002) cost-of-running model behind GAP - same
    formula backend_java's RaceSplitsService.avgGradePct/gapPace must match."""

    def test_flat_grade_leaves_pace_unchanged(self):
        self.assertEqual(_gap_pace_s_per_km(300.0, 0.0), 300.0)

    def test_uphill_grade_gives_a_faster_gap_than_actual_pace(self):
        self.assertEqual(_gap_pace_s_per_km(300.0, 10.0), 181.0)

    def test_downhill_grade_gives_a_slower_gap_than_actual_pace(self):
        self.assertEqual(_gap_pace_s_per_km(300.0, -10.0), 501.9)

    def test_none_pace_or_grade_gives_no_gap(self):
        self.assertIsNone(_gap_pace_s_per_km(None, 5.0))
        self.assertIsNone(_gap_pace_s_per_km(300.0, None))


def _ramp_profile(segments: list[tuple[float, float]], step_m: float = 10.0) -> tuple[list[float], list[float]]:
    """A synthetic elevation profile from (length_m, grade_pct) pieces, starting at 100m - same
    shape as courses.tests.test_sections._ramp, duplicated here to avoid a cross-app test
    import."""
    distances, elevations = [0.0], [100.0]
    for length_m, grade_pct in segments:
        for _ in range(int(length_m / step_m)):
            distances.append(distances[-1] + step_m / 1000.0)
            elevations.append(elevations[-1] + step_m * grade_pct / 100.0)
    return distances, elevations


def _add_constant_pace_records(
    activity,
    distance_km: float,
    pace_s_per_km: float,
    step_s: int = 10,
    power: int | None = None,
    altitude_per_km: float | None = None,
):
    total_s = int(distance_km * pace_s_per_km)
    t = 0
    start = activity.start_date
    while t <= total_s:
        km = t / pace_s_per_km
        Record.objects.create(
            activity=activity,
            t=t,
            ts=start + timedelta(seconds=t),
            distance_km=round(km, 4),
            heartrate=140,
            power=power,
            altitude=km * altitude_per_km if altitude_per_km is not None else None,
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
        Lap.objects.create(activity=self.activity, index=1, duration=300, distance_km=1.0, avg_hr=140, avg_power=255)
        Lap.objects.create(activity=self.activity, index=2, duration=300, distance_km=1.0, avg_hr=142)

        result = get_splits(self.race, "laps")
        self.assertTrue(result["has_laps"])
        self.assertEqual(len(result["splits"]), 2)
        self.assertEqual(result["splits"][0]["time_s"], 300)
        self.assertEqual(result["splits"][1]["start_km"], 1.0)
        self.assertEqual(result["splits"][0]["avg_power"], 255)
        self.assertIsNone(result["splits"][1]["avg_power"])
        # No altitude on this activity -> no grade -> GAP falls back to the raw pace for delta.
        self.assertIsNone(result["splits"][0]["gap_pace_s_per_km"])
        self.assertEqual(result["splits"][0]["delta_vs_ref_s_per_km"], 0.0)

    def test_lap_grade_comes_from_the_activitys_own_altitude(self):
        # Laps have no altitude of their own - this is computed from the activity's records,
        # using each lap's cumulative distance range as the window.
        _add_constant_pace_records(self.activity, distance_km=2.0, pace_s_per_km=300, altitude_per_km=10)
        Lap.objects.create(activity=self.activity, index=1, duration=300, distance_km=1.0)
        Lap.objects.create(activity=self.activity, index=2, duration=300, distance_km=1.0)

        result = get_splits(self.race, "laps")
        self.assertAlmostEqual(result["splits"][0]["avg_grade_pct"], 1.0, delta=0.1)
        self.assertAlmostEqual(result["splits"][1]["avg_grade_pct"], 1.0, delta=0.1)

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
        # A steady +2% grade (20m climbed per km) throughout.
        _add_constant_pace_records(self.activity, distance_km=5.5, pace_s_per_km=300, power=200, altitude_per_km=20)
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
        self.assertEqual(first["avg_power"], 200)
        self.assertAlmostEqual(first["avg_grade_pct"], 2.0, delta=0.1)

    def test_gap_pace_adjusts_for_grade_and_drives_the_delta(self):
        # +2% grade the whole way (see setUp) - GAP should credit the extra climbing effort
        # with a faster flat-equivalent pace than the raw 300s/km, and the delta vs the (flat)
        # goal pace should follow GAP, not the raw pace.
        result = get_splits(self.race, "1")
        first = result["splits"][0]
        self.assertLess(first["gap_pace_s_per_km"], first["pace_s_per_km"])
        self.assertAlmostEqual(first["delta_vs_ref_s_per_km"], first["gap_pace_s_per_km"] - 300, delta=0.1)
        self.assertNotAlmostEqual(first["delta_vs_ref_s_per_km"], 0, delta=5)

    def test_5km_splits_produce_two_splits(self):
        result = get_splits(self.race, "5")
        self.assertEqual(len(result["splits"]), 2)
        self.assertAlmostEqual(result["splits"][0]["end_km"], 5.0, places=2)

    def test_no_linked_activity_returns_empty_splits(self):
        race = Race.objects.create(athlete=self.athlete, name="No activity race", date=self.activity.start_date.date())
        result = get_splits(race, "1")
        self.assertEqual(result["splits"], [])
        self.assertFalse(result["has_laps"])


class PowerDeltaTests(TestCase):
    """GET /v1/races/{id}/splits's delta_power_w - preferred over GAP/delta_vs_ref_s_per_km by
    the frontend whenever the activity has power data, since power already accounts for grade
    mechanically rather than estimating it from a cost model."""

    def setUp(self):
        self.athlete = User.objects.create_user(email="athlete-power@example.cc", password="x", name="Athlete")
        self.activity = _make_activity(
            self.athlete,
            start_date=datetime(2026, 10, 12, 8, 0, tzinfo=UTC),
            distance_km=5.5,
            moving_time=1650,
            avg_power=250,
        )
        _add_constant_pace_records(self.activity, distance_km=5.5, pace_s_per_km=300, power=260)

    def test_power_delta_falls_back_to_activity_average_power_when_no_target_set(self):
        race = Race.objects.create(
            athlete=self.athlete,
            name="5.5k time trial",
            date=self.activity.start_date.date(),
            activity=self.activity,
            distance_km=5.5,
        )
        result = get_splits(race, "1")
        self.assertEqual(result["ref_power"], "average")
        self.assertEqual(result["ref_power_w"], 250)
        self.assertEqual(result["splits"][0]["avg_power"], 260)
        self.assertEqual(result["splits"][0]["delta_power_w"], 10)

    def test_power_delta_uses_target_power_when_set(self):
        race = Race.objects.create(
            athlete=self.athlete,
            name="5.5k time trial",
            date=self.activity.start_date.date(),
            activity=self.activity,
            distance_km=5.5,
            target_power=240,
        )
        result = get_splits(race, "1")
        self.assertEqual(result["ref_power"], "target")
        self.assertEqual(result["ref_power_w"], 240)
        self.assertEqual(result["splits"][0]["delta_power_w"], 20)

    def test_power_delta_is_none_with_no_power_data_at_all(self):
        activity = _make_activity(
            self.athlete, start_date=datetime(2026, 10, 13, 8, 0, tzinfo=UTC), distance_km=5.5, moving_time=1650
        )
        _add_constant_pace_records(activity, distance_km=5.5, pace_s_per_km=300)
        race = Race.objects.create(
            athlete=self.athlete,
            name="No power data",
            date=activity.start_date.date(),
            activity=activity,
            distance_km=5.5,
        )
        result = get_splits(race, "1")
        self.assertEqual(result["ref_power"], "average")
        self.assertIsNone(result["ref_power_w"])
        self.assertIsNone(result["splits"][0]["delta_power_w"])


class ElevationSplitsTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(email="athlete-elev@example.cc", password="x", name="Athlete")
        # 3km flat, 1km @ 3%, 3km flat - the same shape courses.tests.test_sections'
        # test_flat_climb_flat already proves produces exactly [flat, climb, flat] sections
        # under the default CourseSectionSettings.
        distances, elevations = _ramp_profile([(3000, 0.0), (1000, 3.0), (3000, 0.0)])
        quality, series = build_elevation_series(distances, elevations)
        self.course = Course.objects.create(
            athlete=self.athlete,
            name="Elevation course",
            source="gpx",
            distance_km=7.0,
            elevation_quality=quality,
            elevation_series=series,
        )
        self.activity = _make_activity(
            self.athlete, start_date=datetime(2026, 10, 12, 8, 0, tzinfo=UTC), distance_km=7.0, moving_time=2100
        )
        # A fine (1s) step keeps the recorded distance grid fine enough that the climb
        # section's boundaries (2.97km/4.04km - not round numbers) land on a sampled point,
        # same as real GPS data; a coarse step would bias the pace of a split whose boundary
        # falls between two samples.
        _add_constant_pace_records(self.activity, distance_km=7.0, pace_s_per_km=300, step_s=1)
        self.race = Race.objects.create(
            athlete=self.athlete,
            name="Elevation race",
            date=self.activity.start_date.date(),
            activity=self.activity,
            course=self.course,
            distance_km=7.0,
            goal_time=timedelta(minutes=35),  # exactly 300s/km over 7km
        )

    def test_splits_follow_course_sections(self):
        result = get_splits(self.race, "elevation")
        self.assertEqual([s["section_type"] for s in result["splits"]], ["flat", "climb", "flat"])
        climb = result["splits"][1]
        self.assertAlmostEqual(climb["start_km"], 3.0, delta=0.1)
        self.assertAlmostEqual(climb["end_km"], 4.0, delta=0.1)
        self.assertAlmostEqual(climb["pace_s_per_km"], 300, delta=3)
        self.assertGreater(climb["gain_m"], 0)
        self.assertEqual(result["splits"][0]["section_type"], "flat")
        self.assertIsNone(result["splits"][0]["label"])
        self.assertIsNone(climb["avg_power"])  # no power meter on this activity's records

    def test_no_course_returns_empty_splits(self):
        other_activity = _make_activity(
            self.athlete, start_date=datetime(2026, 10, 13, 8, 0, tzinfo=UTC), distance_km=7.0, moving_time=2100
        )
        race = Race.objects.create(
            athlete=self.athlete, name="No course", date=other_activity.start_date.date(), activity=other_activity
        )
        result = get_splits(race, "elevation")
        self.assertEqual(result["splits"], [])

    def test_course_without_elevation_returns_empty_splits(self):
        other_activity = _make_activity(
            self.athlete, start_date=datetime(2026, 10, 13, 8, 0, tzinfo=UTC), distance_km=7.0, moving_time=2100
        )
        course = Course.objects.create(athlete=self.athlete, name="No elevation", source="gpx")
        race = Race.objects.create(
            athlete=self.athlete,
            name="Flat course race",
            date=other_activity.start_date.date(),
            activity=other_activity,
            course=course,
        )
        result = get_splits(race, "elevation")
        self.assertEqual(result["splits"], [])
