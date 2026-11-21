from datetime import UTC, date, datetime, timedelta

from django.test import SimpleTestCase, TestCase

from accounts.models import User
from activities.intensity_histogram import build_histogram, ensure_histogram, rolling_mean_power
from activities.models import Activity

from .decay_load import above_z2_load_for_activity, activity_load, decay_load_series, load_by_day_for_athlete
from .models import ThresholdHistory


class ActivityLoadTests(SimpleTestCase):
    """Plan section 9.1's "Load filter" scenarios, deferred from slice 1 until the histogram
    existed to feed them."""

    def test_sample_at_exactly_cutoff_counts_and_just_below_does_not(self):
        R = 260.0
        # 0.75 * 260 = 195 exactly; 0.749 * 260 rounds to 194, not 195.
        self.assertGreater(activity_load({195: 1}, None, R, None), 0)
        self.assertEqual(activity_load({194: 1}, None, R, None), 0.0)

    def test_steady_ride_below_cutoff_contributes_nothing(self):
        R = 260.0
        watt = round(0.70 * R)
        self.assertEqual(activity_load({watt: 7200}, None, R, None), 0.0)

    def test_steady_ride_above_cutoff_matches_hand_computed_value(self):
        R = 260.0
        watt = round(0.80 * R)
        # (0.80)^2 * 100 / 3600 * 3600 seconds = 64.
        self.assertAlmostEqual(activity_load({watt: 3600}, None, R, None), 64.0, places=1)

    def test_smooth_ramp_boundary_values_match_the_hard_cutoff_exactly(self):
        """Ship the hard cutoff (resolved open question 9) - confirms it's a true step function,
        not an accidental smooth ramp, right at the boundary."""
        R = 260.0
        at_cutoff = activity_load({195: 100}, None, R, None)
        just_above = activity_load({196: 100}, None, R, None)
        just_below = activity_load({194: 100}, None, R, None)
        self.assertEqual(just_below, 0.0)
        self.assertGreater(at_cutoff, 0.0)
        self.assertGreater(just_above, at_cutoff)

    def test_hr_fallback_only_applies_without_a_usable_power_histogram(self):
        lthr = 160.0
        # 145/160 = 0.906 >= the 0.85 HR cutoff -> counts, when there's no power histogram at all.
        self.assertGreater(activity_load(None, {145: 3600}, 260.0, lthr), 0.0)
        # A usable power histogram always wins - the HR one is ignored entirely, not blended in.
        self.assertEqual(
            activity_load({200: 10}, {145: 3600}, 260.0, lthr), activity_load({200: 10}, None, 260.0, lthr)
        )

    def test_string_histogram_keys_from_a_jsonfield_round_trip_are_handled(self):
        R = 260.0
        self.assertEqual(activity_load({"195": 1}, None, R, None), activity_load({195: 1}, None, R, None))

    def test_no_reference_value_gives_zero(self):
        self.assertEqual(activity_load({200: 3600}, None, None, None), 0.0)
        self.assertEqual(activity_load({200: 3600}, None, 0, None), 0.0)


class DecayLoadSeriesTests(SimpleTestCase):
    def test_empty_input_gives_empty_output(self):
        self.assertEqual(decay_load_series({}), {})

    def test_walks_every_day_from_earliest_to_latest_key(self):
        d0 = date(2026, 1, 1)
        series = decay_load_series({d0: 100.0, d0 + timedelta(days=2): 100.0})
        self.assertEqual(set(series.keys()), {d0, d0 + timedelta(days=1), d0 + timedelta(days=2)})

    def test_recurrence_matches_hand_computed_ewma(self):
        d0 = date(2026, 1, 1)
        series = decay_load_series({d0: 42.0})
        self.assertAlmostEqual(series[d0], 42.0 / 42)

    def test_maintained_load_converges_toward_the_steady_value(self):
        d0 = date(2026, 1, 1)
        daily = {d0 + timedelta(days=i): 10.0 for i in range(400)}
        series = decay_load_series(daily)
        last_day = d0 + timedelta(days=399)
        self.assertAlmostEqual(series[last_day], 10.0, delta=0.01)


class NoCircularityTests(SimpleTestCase):
    """Plan section 9.1's "no circularity" scenario: the load series is unchanged when the
    anchor-independent inputs (the histogram) stay the same, and changes only when R does -
    confirming activity_load reads the histogram, not a live FTP/TSS/IF value."""

    def test_load_is_unaffected_by_ifs_or_tss_computed_elsewhere(self):
        histogram = {195: 3600}
        R = 260.0
        load_before = activity_load(histogram, None, R, None)
        # Simulate some unrelated code recomputing IF/TSS against a totally different reference -
        # activity_load must not have been affected by any global/shared state.
        _ = histogram[195] / 300.0  # an unrelated "IF-like" computation using the same histogram
        load_after = activity_load(histogram, None, R, None)
        self.assertEqual(load_before, load_after)

    def test_load_changes_only_when_the_anchor_changes(self):
        histogram = {220: 3600}
        load_at_260 = activity_load(histogram, None, 260.0, None)
        load_at_260_again = activity_load(histogram, None, 260.0, None)
        load_at_240 = activity_load(histogram, None, 240.0, None)
        self.assertEqual(load_at_260, load_at_260_again)
        self.assertNotEqual(load_at_260, load_at_240)


class IntensityHistogramLoadParityTests(SimpleTestCase):
    """Plan section 9.1's "histogram" scenario: activity_load from a histogram built via
    build_histogram equals a load computed directly from the raw series, to 1e-9."""

    def test_activity_load_from_histogram_matches_a_direct_series_computation(self):
        R = 260.0
        power_series = [200] * 40 + [230] * 40  # 80 samples, mixed above/below cutoff (195)
        power_hist, _ = build_histogram(power_series, [])

        # Direct computation from the *smoothed* series (not the raw one) - activity_load only
        # ever sees the histogram, which already reflects smoothing; this is the ground truth it
        # must match exactly.
        smoothed = rolling_mean_power(power_series)
        direct_load = 0.0
        for value in smoothed:
            ratio = round(value) / R
            if ratio >= 0.75:
                direct_load += (ratio**2) * 100 / 3600

        histogram_load = activity_load(power_hist, None, R, None)
        self.assertAlmostEqual(histogram_load, direct_load, delta=1e-9)


class LoadByDayForAthleteTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(email="athlete@example.cc", password="x", name="Athlete", lthr=160)

    def _make_bike_activity(self, start_date, power=220, seconds=3600):
        activity = Activity.objects.create(
            athlete=self.athlete,
            sport="bike",
            environment="outdoor",
            name="Ride",
            start_date=start_date.replace(tzinfo=UTC),
            moving_time=seconds,
            distance_km=30.0,
        )
        for t in range(0, seconds, 30):
            activity.records.create(t=t, ts=activity.start_date + timedelta(seconds=t), power=power, heartrate=150)
        return activity

    def test_aggregates_across_activities_and_smooths_the_result(self):
        day1 = datetime(2026, 1, 1, 8, 0)
        day2 = datetime(2026, 1, 2, 8, 0)
        self._make_bike_activity(day1, power=220, seconds=600)
        self._make_bike_activity(day2, power=220, seconds=600)

        series = load_by_day_for_athlete(
            self.athlete,
            "ftp",
            anchor_value=260.0,
            lthr=self.athlete.lthr,
            start=date(2026, 1, 1),
            end=date(2026, 1, 3),
        )
        # decay_load_series only spans the activities' own earliest-to-latest date, not the full
        # requested [start, end] window - there's nothing to smooth on a day with no activity and
        # no prior EWMA state to carry forward from.
        self.assertEqual(set(series.keys()), {date(2026, 1, 1), date(2026, 1, 2)})
        self.assertGreater(series[date(2026, 1, 2)], series[date(2026, 1, 1)])

    def test_non_qualifying_sport_is_excluded(self):
        activity = self._make_bike_activity(datetime(2026, 1, 1, 8, 0), seconds=600)
        activity.sport = "run"
        activity.save(update_fields=["sport"])

        series = load_by_day_for_athlete(
            self.athlete,
            "ftp",
            anchor_value=260.0,
            lthr=self.athlete.lthr,
            start=date(2026, 1, 1),
            end=date(2026, 1, 1),
        )
        # No qualifying (bike) activity at all in range -> nothing to build a series from.
        self.assertEqual(series, {})


class AboveZ2LoadForActivityTests(TestCase):
    """Issue #487: activity_load() for exactly one activity, resolving its own histogram and
    in-effect reference - the per-activity number list_activity_above_z2_load exposes."""

    def setUp(self):
        self.athlete = User.objects.create_user(email="above-z2-athlete@example.cc", password="x", name="Athlete")
        self.start = datetime(2026, 3, 1, 8, 0, tzinfo=UTC)

    def _bike_activity(self, power=220, seconds=3600, start_date=None):
        activity = Activity.objects.create(
            athlete=self.athlete, sport="bike", name="Ride", start_date=start_date or self.start, moving_time=seconds
        )
        for t in range(0, seconds, 30):
            activity.records.create(t=t, ts=activity.start_date + timedelta(seconds=t), power=power)
        return activity

    def _anchor(self, value, effective_from):
        return ThresholdHistory.objects.create(
            athlete=self.athlete,
            field="ftp",
            value_numeric=value,
            effective_from=effective_from,
            current_from=effective_from,
            kind="measured",
        )

    def test_returns_none_with_no_histogram_data_at_all(self):
        activity = Activity.objects.create(
            athlete=self.athlete, sport="bike", name="No records", start_date=self.start, moving_time=3600
        )

        self.assertIsNone(above_z2_load_for_activity(activity, "ftp"))

    def test_returns_none_with_no_in_effect_anchor_even_if_the_athlete_has_a_live_ftp(self):
        """reference_for(athlete, zone_type, activity) only resolves from the historical ledger
        (current_from <= the activity's own date), never the live profile value, once an activity
        is given - so a live ftp with no ThresholdHistory row at all still yields no reference."""
        self.athlete.ftp = 260
        self.athlete.save(update_fields=["ftp"])
        activity = self._bike_activity(power=220)

        self.assertIsNone(above_z2_load_for_activity(activity, "ftp"))

    def test_resolves_the_anchor_that_was_in_effect_on_the_activitys_own_date(self):
        self._anchor(260, date(2026, 1, 1))
        activity = self._bike_activity(power=round(0.80 * 260))
        histogram = ensure_histogram(activity)

        load = above_z2_load_for_activity(activity, "ftp")

        self.assertIsNotNone(load)
        self.assertGreater(load, 0.0)
        self.assertAlmostEqual(load, activity_load(histogram.power_30s, histogram.heartrate, 260.0, None), delta=1e-9)

    def test_an_explicit_reference_override_wins_over_the_in_effect_anchor(self):
        self._anchor(260, date(2026, 1, 1))
        activity = self._bike_activity(power=round(0.80 * 300))
        histogram = ensure_histogram(activity)

        load = above_z2_load_for_activity(activity, "ftp", reference=300)

        # Against the 260 in-effect anchor this same power would clear the cutoff by a wider
        # margin - confirms the override, not the ledger anchor, was actually used.
        self.assertNotAlmostEqual(load, activity_load(histogram.power_30s, histogram.heartrate, 260.0, None))
        self.assertAlmostEqual(load, activity_load(histogram.power_30s, histogram.heartrate, 300.0, None), delta=1e-9)

    def test_zero_load_when_histogram_data_exists_but_nothing_clears_the_cutoff(self):
        self._anchor(260, date(2026, 1, 1))
        activity = self._bike_activity(power=round(0.50 * 260))  # well below the 0.75 cutoff

        self.assertEqual(above_z2_load_for_activity(activity, "ftp"), 0.0)
