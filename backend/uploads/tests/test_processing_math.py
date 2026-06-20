from django.test import SimpleTestCase, TestCase

from accounts.models import User
from activities.models import Activity

from ..processing import compute_duration_curve, compute_normalized_power, compute_tss


class ComputeNormalizedPowerTests(SimpleTestCase):
    def test_constant_power_equals_average(self):
        self.assertAlmostEqual(compute_normalized_power([200] * 100), 200, places=3)

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


class ComputeTssTests(TestCase):
    def test_power_based_tss_one_hour_at_ftp_equals_100(self):
        athlete = User.objects.create_user(email="ftp@example.cc", password="x", name="FTP Athlete", ftp=200)
        activity = Activity(sport="bike", moving_time=3600)
        tss = compute_tss(activity, athlete, norm_power=200, heartrate_series=[])
        self.assertEqual(tss, 100)

    def test_hr_based_fallback_uses_zone_midpoint(self):
        athlete = User.objects.create_user(email="lthr@example.cc", password="x", name="LTHR Athlete", lthr=160)
        activity = Activity(sport="bike", moving_time=3600)
        tss = compute_tss(activity, athlete, norm_power=None, heartrate_series=[160] * 3600)
        # All samples sit at exactly 100% of LTHR -> Z4 Threshold (91-105%),
        # whose midpoint is 98% -> a full hour there is 98 hrTSS exactly.
        self.assertEqual(tss, 98)
