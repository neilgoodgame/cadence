from datetime import UTC, datetime, timedelta

from django.core.files.uploadedfile import SimpleUploadedFile
from django.test import SimpleTestCase, TestCase

from accounts.models import User

from ..intensity_histogram import build_histogram, ensure_histogram, rebuild_histogram, rolling_mean_power
from ..models import Activity, ActivityIntensityHistogram, Record
from .helpers import _bearer_client, _make_activity


class RollingMeanPowerTests(SimpleTestCase):
    def test_empty_series_returns_empty(self):
        self.assertEqual(rolling_mean_power([]), [])

    def test_shorter_than_window_returns_single_average(self):
        self.assertEqual(rolling_mean_power([100, 200, 300], window=30), [200.0])

    def test_matches_a_hand_computed_window(self):
        series = [100] * 30 + [200] * 30
        rolling = rolling_mean_power(series, window=30)
        self.assertEqual(len(rolling), len(series) - 30 + 1)
        self.assertEqual(rolling[0], 100.0)
        self.assertEqual(rolling[-1], 200.0)

    def test_none_samples_are_zero_filled(self):
        self.assertEqual(rolling_mean_power([None, None], window=30), [0.0])


class BuildHistogramTests(SimpleTestCase):
    def test_tallies_seconds_per_whole_watt(self):
        # 30 identical samples collapse to one 30s-smoothed point -> one bucket, one second.
        power_hist, _ = build_histogram([200] * 30, [])
        self.assertEqual(power_hist, {200: 1})

    def test_no_power_data_gives_none(self):
        power_hist, hr_hist = build_histogram([None, None], [None, None])
        self.assertIsNone(power_hist)
        self.assertIsNone(hr_hist)

    def test_heartrate_is_tallied_raw_not_smoothed(self):
        _, hr_hist = build_histogram([], [150, 150, 160])
        self.assertEqual(hr_hist, {150: 2, 160: 1})

    def test_rounds_to_whole_numbers(self):
        power_hist, _ = build_histogram([200.4, 200.6], [])
        # Both samples are in the same <30-sample short-activity average bucket (200.5 -> 200 or
        # 201 depending on rounding direction) - just confirm it's a single whole-number key.
        self.assertEqual(len(power_hist), 1)
        (key,) = power_hist.keys()
        self.assertIsInstance(key, int)


class RebuildHistogramTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(email="athlete@example.cc", password="x", name="Athlete")

    def _make_activity_with_records(self, sport="bike", power=200, heartrate=150, seconds=60):
        activity = _make_activity(self.athlete, sport=sport, moving_time=seconds)
        for t in range(seconds):
            Record.objects.create(
                activity=activity, t=t, ts=activity.start_date + timedelta(seconds=t), power=power, heartrate=heartrate
            )
        return activity

    def test_rebuild_builds_and_saves_from_given_series(self):
        activity = self._make_activity_with_records()
        histogram = rebuild_histogram(activity, [200] * 60, [150] * 60)
        # Power is smoothed first: 60 samples over a 30s window collapse to 60-30+1=31 rolling
        # points, each counting as one second - heart rate is tallied raw, so it's a plain 60.
        self.assertEqual(sum(histogram.power_30s.values()), 31)
        self.assertEqual(histogram.heartrate, {150: 60})

    def test_rebuild_queries_its_own_records_when_no_series_given(self):
        activity = self._make_activity_with_records(power=210, heartrate=155, seconds=45)
        histogram = rebuild_histogram(activity)
        self.assertEqual(sum(histogram.power_30s.values()), 45 - 30 + 1)
        self.assertEqual(histogram.heartrate, {155: 45})

    def test_self_queried_run_activity_is_gated_by_running_power_preference(self):
        self.athlete.running_power_source = "native"
        self.athlete.save(update_fields=["running_power_source"])
        activity = self._make_activity_with_records(sport="run", power=300, seconds=40)
        activity.power_source = "stryd"
        activity.save(update_fields=["power_source"])

        histogram = rebuild_histogram(activity)
        self.assertIsNone(histogram.power_30s)

    def test_ensure_histogram_builds_once_then_reuses_the_stored_row(self):
        activity = self._make_activity_with_records(power=190, seconds=10)
        self.assertEqual(ActivityIntensityHistogram.objects.count(), 0)

        first = ensure_histogram(activity)
        self.assertEqual(ActivityIntensityHistogram.objects.count(), 1)

        # Mutate the stored row directly - ensure_histogram must return THIS, not rebuild. A
        # fresh DB read round-trips JSONField keys to strings, same as every other read below.
        first.power_30s = {1: 999}
        first.save()
        second = ensure_histogram(activity)
        self.assertEqual(second.power_30s, {"1": 999})

    def test_rebuild_is_idempotent_via_update_or_create(self):
        activity = self._make_activity_with_records(power=180, seconds=5)
        rebuild_histogram(activity)
        rebuild_histogram(activity, [185] * 5, [])
        self.assertEqual(ActivityIntensityHistogram.objects.count(), 1)
        histogram = ActivityIntensityHistogram.objects.get(activity=activity)
        # Shorter than the 30s window -> one whole-activity-average bucket, not one per sample.
        self.assertEqual(histogram.power_30s, {"185": 1})

    def test_deleting_the_activity_cascades(self):
        activity = self._make_activity_with_records(seconds=5)
        rebuild_histogram(activity)
        self.assertEqual(ActivityIntensityHistogram.objects.count(), 1)
        activity.delete()
        self.assertEqual(ActivityIntensityHistogram.objects.count(), 0)


class RecomputeStatsHookTests(TestCase):
    """Hook point #2 (backfill_extended_stats) via the real "Recompute stats" endpoint - see
    threshold-decay.md section 5's "written... by recompute-stats, single and bulk"."""

    def setUp(self):
        self.athlete = User.objects.create_user(email="athlete@example.cc", password="x", name="Athlete")

    def test_recompute_stats_builds_a_histogram_with_sane_totals(self):
        activity = _make_activity(self.athlete, sport="bike", moving_time=120)
        for t in range(120):
            Record.objects.create(
                activity=activity, t=t, ts=activity.start_date + timedelta(seconds=t), power=210, heartrate=145
            )
        self.assertFalse(ActivityIntensityHistogram.objects.filter(activity=activity).exists())

        response = _bearer_client(self.athlete).post(f"/v1/activities/{activity.id}/recompute-stats")
        self.assertEqual(response.status_code, 200)

        histogram = ActivityIntensityHistogram.objects.get(activity=activity)
        self.assertEqual(sum(histogram.power_30s.values()), 120 - 30 + 1)
        self.assertEqual(sum(histogram.heartrate.values()), 120)


class IngestHookTests(TestCase):
    """Hook point #1 (_ingest_activity) via a real upload, end to end."""

    def setUp(self):
        self.athlete = User.objects.create_user(email="athlete@example.cc", password="x", name="Athlete")

    def test_uploading_an_activity_builds_a_histogram(self):
        from uploads.fixtures_helpers import build_tcx

        start = datetime(2026, 6, 1, 8, 0, tzinfo=UTC)
        content = build_tcx(start, sport="Biking", duration_s=90, power=220, hr=148)
        client = _bearer_client(self.athlete)

        response = client.post("/v1/activities", {"file": SimpleUploadedFile("ride.tcx", content)}, format="multipart")
        self.assertEqual(response.status_code, 202)
        activity_id = client.get(f"/v1/uploads/{response.json()['id']}").json()["activity_id"]
        activity = Activity.objects.get(pk=activity_id)

        histogram = ActivityIntensityHistogram.objects.get(activity=activity)
        # Power is smoothed first: ~90 samples over a 30s window collapse to ~90-30+1=61 rolling
        # points - a couple of seconds' tolerance for TCX timestamp rounding either way.
        self.assertAlmostEqual(sum(histogram.power_30s.values()), 90 - 30 + 1, delta=2)
        self.assertAlmostEqual(sum(histogram.heartrate.values()), 90, delta=2)
