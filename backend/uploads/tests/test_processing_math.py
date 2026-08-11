from django.test import SimpleTestCase, TestCase

from accounts.models import User
from activities.models import Activity

from ..processing import (
    _best_pace_seconds_per_km,
    _best_pace_seconds_per_km_over_duration,
    _min,
    _total_ascent,
    _total_descent,
    compute_calories,
    compute_duration_curve,
    compute_edwards_trimp,
    compute_normalized_power,
    compute_tss,
    detect_threshold_increase,
    training_effect_label,
)


class ComputeNormalizedPowerTests(SimpleTestCase):
    def test_constant_power_equals_average(self):
        result = compute_normalized_power([200] * 100)
        assert result is not None
        self.assertAlmostEqual(result, 200, places=3)

    def test_short_series_falls_back_to_plain_mean(self):
        self.assertEqual(compute_normalized_power([100, 200, 300]), 200)

    def test_empty_series_returns_none(self):
        self.assertIsNone(compute_normalized_power([]))


class ComputeDurationCurveTests(SimpleTestCase):
    def test_picks_best_window_average(self):
        series = [100] * 10 + [300] * 5 + [100] * 10
        points = compute_duration_curve(series, [5])
        self.assertEqual(points["5"], 300.0)

    def test_omits_windows_longer_than_series(self):
        points = compute_duration_curve([100] * 10, [5, 20])
        self.assertIn("5", points)
        self.assertNotIn("20", points)

    def test_extends_to_full_series_when_longer_than_the_standard_windows(self):
        # An activity over an hour: the curve should add one more point at the full
        # length, valued as the whole-activity average - exactly what the API's
        # extends_to field on the resulting DurationCurve documents happening.
        series = [200] * 3600 + [100] * 1800  # 1h at 200, then 30 more min at 100
        points = compute_duration_curve(series, [5, 60, 3600])
        self.assertIn("5400", points)
        self.assertAlmostEqual(points["5400"], (200 * 3600 + 100 * 1800) / 5400, places=1)

    def test_does_not_extend_when_series_is_no_longer_than_the_standard_windows(self):
        points = compute_duration_curve([100] * 3600, [5, 60, 3600])
        self.assertEqual(set(points), {"5", "60", "3600"})


class BestPaceSecondsPerKmTests(SimpleTestCase):
    def test_constant_pace_returns_that_pace(self):
        # 1 km every 300 seconds, 10 km total -> 300 sec/km throughout.
        series = [i / 300 for i in range(3001)]
        t = list(range(len(series)))
        self.assertAlmostEqual(_best_pace_seconds_per_km(t, series, 1.0), 300.0, places=1)

    def test_finds_the_genuinely_fastest_window_not_the_first_one(self):
        # Hand-traced in the plan: 0, 0.5, 1.5, 2.5, 3.0 km at t=0..4. The fastest 1km
        # split is the single second from t=1 to t=2 (exactly 1.0 km in 1 second),
        # not the first qualifying window found (t=0 to t=2, 1.5km in 2 seconds).
        series = [0, 0.5, 1.5, 2.5, 3.0]
        t = list(range(len(series)))
        self.assertAlmostEqual(_best_pace_seconds_per_km(t, series, 1.0), 1.0, places=3)

    def test_returns_none_when_target_distance_is_never_reached(self):
        series = [i / 300 for i in range(601)]  # 2 km total
        t = list(range(len(series)))
        self.assertIsNone(_best_pace_seconds_per_km(t, series, 5.0))

    def test_forward_fills_gaps_instead_of_treating_them_as_a_reset(self):
        # A None sample (brief GPS dropout) should read as "distance unchanged," not zero -
        # same convention _total_distance_km already relies on. The full 2.0 km span
        # (index 0 to 4) takes 4 seconds, so the pace is 4 / 2.0 = 2.0 sec/km.
        series = [0, 0.5, None, None, 2.0]
        t = list(range(len(series)))
        self.assertAlmostEqual(_best_pace_seconds_per_km(t, series, 2.0), 2.0, places=3)

    def test_uses_real_elapsed_time_not_sample_index_for_sparse_recording(self):
        # Some devices ("smart"/adaptive recording) log a sample every few seconds
        # instead of every second. 2 km covered over samples at t=0, 60, 120 (real
        # elapsed time 120s) must come out as 60 sec/km - not 2 sec/km, which is what
        # you'd get from mistaking the 2-sample gap (index 0 to index 2) for 2 seconds.
        t = [0, 60, 120]
        series = [0, 1.0, 2.0]
        self.assertAlmostEqual(_best_pace_seconds_per_km(t, series, 2.0), 60.0, places=3)


class BestPaceSecondsPerKmOverDurationTests(SimpleTestCase):
    """The dual of BestPaceSecondsPerKmTests above: fixed *time* target, variable *distance*
    window, instead of fixed distance/variable time."""

    def test_constant_pace_returns_that_pace(self):
        # 1 km every 60 seconds, sustained for a full hour -> 60 sec/km throughout.
        series = [i / 60 for i in range(3601)]
        t = list(range(len(series)))
        self.assertAlmostEqual(_best_pace_seconds_per_km_over_duration(t, series, 3600), 60.0, places=1)

    def test_returns_none_when_target_duration_is_never_reached(self):
        series = [i / 60 for i in range(100)]  # only ~99 seconds of data
        t = list(range(len(series)))
        self.assertIsNone(_best_pace_seconds_per_km_over_duration(t, series, 3600))

    def test_uses_real_elapsed_time_not_sample_index_for_sparse_recording(self):
        # 10 km covered over samples at t=0 and t=3600 (real elapsed time exactly one hour)
        # must come out as 360 sec/km.
        t = [0, 3600]
        series = [0, 10.0]
        self.assertAlmostEqual(_best_pace_seconds_per_km_over_duration(t, series, 3600), 360.0, places=3)

    def test_forward_fills_gaps_instead_of_treating_them_as_a_reset(self):
        t = list(range(3601))
        series = [None] * 3601
        series[0] = 0.0
        series[3600] = 60.0  # 60 km in one hour -> 60 sec/km, with everything in between missing
        self.assertAlmostEqual(_best_pace_seconds_per_km_over_duration(t, series, 3600), 60.0, places=3)


class DetectThresholdIncreaseTests(TestCase):
    """detect_threshold_increase - the actual detection formulas: bike FTP is 95% of the best
    20-minute power, run critical_run_power/threshold_pace come directly from the best 60-minute
    effort. Only ever suggests an *increase* (or, for pace, a *faster* time)."""

    def test_bike_suggests_ftp_at_95_percent_of_best_20min_power(self):
        activity = Activity(sport="bike", ftp_snapshot=200)
        # A steady 280W for the full 20-minute window -> implied FTP = round(0.95 * 280) = 266.
        power_series = [280] * 1200
        detect_threshold_increase(activity, power_series, list(range(1200)), [None] * 1200)
        self.assertEqual(activity.suggested_ftp, 266)

    def test_bike_does_not_suggest_a_decrease(self):
        activity = Activity(sport="bike", ftp_snapshot=300)
        power_series = [200] * 1200  # implies ~190W FTP - well below the 300 snapshot
        detect_threshold_increase(activity, power_series, list(range(1200)), [None] * 1200)
        self.assertIsNone(activity.suggested_ftp)

    def test_bike_activity_shorter_than_the_20min_window_suggests_nothing(self):
        activity = Activity(sport="bike", ftp_snapshot=200)
        power_series = [280] * 600  # only 10 minutes
        detect_threshold_increase(activity, power_series, list(range(600)), [None] * 600)
        self.assertIsNone(activity.suggested_ftp)

    def test_run_suggests_critical_run_power_directly_from_best_60min_power(self):
        activity = Activity(sport="run", critical_run_power_snapshot=250)
        power_series = [300] * 3600
        detect_threshold_increase(activity, power_series, list(range(3600)), [None] * 3600)
        self.assertEqual(activity.suggested_critical_run_power, 300)

    def test_run_suggests_threshold_pace_from_best_60min_pace(self):
        activity = Activity(sport="run", threshold_pace_snapshot="4:30")
        t = list(range(3601))
        distance_km_series = [None] * 3601
        distance_km_series[0] = 0.0
        distance_km_series[3600] = 15.0  # 15km in an hour -> 240 sec/km -> "4:00"
        detect_threshold_increase(activity, [None] * 3601, t, distance_km_series)
        self.assertEqual(activity.suggested_threshold_pace, "4:00")

    def test_run_does_not_suggest_a_slower_pace(self):
        activity = Activity(sport="run", threshold_pace_snapshot="4:00")
        t = list(range(3601))
        distance_km_series = [None] * 3601
        distance_km_series[0] = 0.0
        distance_km_series[3600] = 12.0  # 300 sec/km ("5:00") - slower than the 4:00 snapshot
        detect_threshold_increase(activity, [None] * 3601, t, distance_km_series)
        self.assertEqual(activity.suggested_threshold_pace, "")

    def test_multisport_and_other_sports_never_suggest_anything(self):
        activity = Activity(sport="swim")
        detect_threshold_increase(activity, [300] * 3600, list(range(3600)), [None] * 3600)
        self.assertIsNone(activity.suggested_ftp)
        self.assertIsNone(activity.suggested_critical_run_power)
        self.assertEqual(activity.suggested_threshold_pace, "")


class ComputeTssTests(TestCase):
    def test_power_based_tss_one_hour_at_ftp_equals_100(self):
        # compute_tss reads the activity's own threshold snapshot, not the athlete's live
        # profile (see compute_tss's docstring) - athlete.ftp is set too, but only to confirm
        # it's NOT what gets read.
        athlete = User.objects.create_user(email="ftp@example.cc", password="x", name="FTP Athlete", ftp=999)
        activity = Activity(sport="bike", moving_time=3600, ftp_snapshot=200)
        tss = compute_tss(activity, athlete, norm_power=200, heartrate_series=[])
        self.assertEqual(tss, 100)

    def test_hr_based_fallback_uses_zone_midpoint(self):
        athlete = User.objects.create_user(email="lthr@example.cc", password="x", name="LTHR Athlete", lthr=160)
        activity = Activity(sport="bike", moving_time=3600)
        tss = compute_tss(activity, athlete, norm_power=None, heartrate_series=[160] * 3600)
        # All samples sit at exactly 100% of LTHR -> Z4 Threshold (91-105%),
        # whose midpoint is 98% -> a full hour there is 98 hrTSS exactly.
        self.assertEqual(tss, 98)


class MinTests(SimpleTestCase):
    def test_ignores_nones(self):
        self.assertEqual(_min([None, 5, 2, None, 8]), 2)

    def test_empty_or_all_none_returns_none(self):
        self.assertIsNone(_min([]))
        self.assertIsNone(_min([None, None]))


class TotalAscentDescentTests(SimpleTestCase):
    def test_sensor_noise_on_flat_ground_does_not_accumulate(self):
        # +/-1m back-and-forth noise around a flat baseline for several minutes - there's no
        # real net elevation change here. Unsmoothed, this used to sum every single positive
        # micro-fluctuation (see _smoothed_altitudes' docstring for the real-world case this
        # was found from: a 515m-vs-actual-~400m Leeds Marathon ascent).
        noise_cycle = [0, 1, 0, -1]
        altitudes = [100 + noise_cycle[i % len(noise_cycle)] for i in range(200)]
        samples = [{"altitude": a} for a in altitudes]
        self.assertLess(_total_ascent(samples), 10)
        self.assertLess(_total_descent(samples), 10)

    def test_sustained_climb_survives_smoothing(self):
        # A genuine, sustained 99.5m climb over 200 samples must still register close to the
        # true gain - smoothing should filter noise, not real elevation change.
        altitudes = [100 + i * 0.5 for i in range(200)]
        samples = [{"altitude": a} for a in altitudes]
        self.assertGreater(_total_ascent(samples), 80)

    def test_fewer_than_two_altitude_samples_returns_none(self):
        self.assertIsNone(_total_ascent([{"altitude": 100}]))
        self.assertIsNone(_total_descent([{"altitude": 100}]))
        self.assertIsNone(_total_descent([{"altitude": None}, {"altitude": None}]))


class ComputeCaloriesTests(SimpleTestCase):
    def test_power_based_estimate(self):
        # 200W for 3600s -> 720 kJ of work -> /0.24 efficiency -> 3000 kJ metabolic
        # energy -> /4.184 kJ-per-kcal -> ~717 kcal.
        self.assertEqual(compute_calories([200] * 3600, 3600), 717)

    def test_no_power_data_returns_none(self):
        self.assertIsNone(compute_calories([None, None], 3600))


class ComputeEdwardsTrimpTests(TestCase):
    def test_one_hour_at_threshold_zone_weights_by_zone_number(self):
        athlete = User.objects.create_user(email="trimp@example.cc", password="x", name="Trimp Athlete", lthr=160)
        # All samples at 100% of LTHR -> Z4 Threshold (zone 4) -> 60 min * 4 = 240.
        trimp = compute_edwards_trimp(athlete, [160] * 3600)
        self.assertEqual(trimp, 240.0)

    def test_no_lthr_returns_none(self):
        athlete = User.objects.create_user(email="notrimp@example.cc", password="x", name="No Threshold Athlete")
        self.assertIsNone(compute_edwards_trimp(athlete, [140] * 600))


class TrainingEffectLabelTests(SimpleTestCase):
    def test_none_returns_empty_string(self):
        self.assertEqual(training_effect_label(None), "")

    def test_boundaries_map_to_garmins_documented_scale(self):
        cases = [
            (0.0, "No Benefit"),
            (0.9, "No Benefit"),
            (1.0, "Minor Benefit"),
            (1.9, "Minor Benefit"),
            (2.0, "Maintaining"),
            (2.9, "Maintaining"),
            (3.0, "Improving"),
            (3.9, "Improving"),
            (4.0, "Highly Improving"),
            (4.9, "Highly Improving"),
            (5.0, "Overreaching"),
        ]
        for value, label in cases:
            self.assertEqual(training_effect_label(value), label, f"value={value}")
