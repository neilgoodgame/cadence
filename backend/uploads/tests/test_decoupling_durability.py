from datetime import UTC, date, datetime

from django.test import SimpleTestCase, TestCase

from accounts.models import User
from activities.models import Activity
from athletes.models import ThresholdHistory

from ..processing import (
    DECOUPLING_MIN_STEADY_SECONDS,
    _sliding_window_best_avg,
    _steady_window_start_index,
    check_decoupling_qualification,
    compute_decoupling,
    compute_durability_rows,
)


class SteadyWindowStartIndexTests(SimpleTestCase):
    def test_continuous_recording_ends_warmup_at_exactly_600(self):
        t = list(range(4000))
        self.assertEqual(_steady_window_start_index(t), 600)

    def test_a_long_pause_contributes_at_most_one_second_to_the_active_clock(self):
        # 300 real seconds, then a huge gap (device paused), then recording resumes - every
        # recorded sample advances the active clock by exactly 1, gap or not (the gap itself
        # is capped to contributing 1, same as any other step), so the warm-up cutoff still
        # lands at sample index 600 - identical to a fully continuous recording - rather than
        # being pushed out by the gap's real wall-clock duration.
        t = list(range(300)) + [100_000 + i for i in range(400)]
        start = _steady_window_start_index(t)
        self.assertEqual(start, 600)

    def test_too_short_to_ever_reach_warmup_returns_none(self):
        t = list(range(500))
        self.assertIsNone(_steady_window_start_index(t))

    def test_empty_series_returns_none(self):
        self.assertIsNone(_steady_window_start_index([]))


def _steady_series(seconds=4000, power=200, hr=140):
    return [power] * seconds, [hr] * seconds


# Wraps check_decoupling_qualification with the design spec's own default thresholds (now
# athlete-configurable - see User.decoupling_vi_limit_bike/_run/decoupling_if_limit/
# decoupling_min_steady_minutes), so these pure-math tests don't need to repeat them at every
# call site. A test that cares about a non-default threshold overrides it explicitly.
def _check(sport, power, hr, threshold, vi_limit=None, if_limit=0.85, min_steady_seconds=3600):
    if vi_limit is None:
        vi_limit = 1.06 if sport == "bike" else 1.04
    return check_decoupling_qualification(sport, power, hr, threshold, vi_limit, if_limit, min_steady_seconds)


class CheckDecouplingQualificationTests(SimpleTestCase):
    def test_qualifies_a_clean_steady_session(self):
        power, hr = _steady_series()
        result = _check("bike", power, hr, threshold=250)
        self.assertTrue(result["qualified"])
        self.assertEqual(result["reasons"], [])
        self.assertAlmostEqual(result["vi"], 1.0, places=2)
        self.assertAlmostEqual(result["if"], 0.8, places=2)

    def test_too_variable_fails_vi(self):
        # Sustained (not sample-to-sample - that gets smoothed away by NP's own 30s rolling
        # average) 120s-on/120s-off surges push NP well above the plain average: VI ~1.31.
        power = (([400] * 120 + [100] * 120) * 15)[:3600]
        hr = [140] * 3600
        result = _check("bike", power, hr, threshold=500)
        self.assertIn("variable", result["reasons"])

    def test_run_has_a_tighter_vi_limit_than_bike(self):
        # 40s-on/40s-off surging lands VI at 1.05 (computed) - just over run's 1.04 limit,
        # just under bike's 1.06 one.
        power = (([270] * 40 + [155] * 40) * 45)[:3600]
        hr = [140] * 3600
        bike_result = _check("bike", power, hr, threshold=500)
        run_result = _check("run", power, hr, threshold=500)
        self.assertNotIn("variable", bike_result["reasons"])
        self.assertIn("variable", run_result["reasons"])

    def test_configured_vi_limit_overrides_the_default(self):
        # The same 1.05-VI surge pattern as above - a bike athlete who's tightened their own
        # limit to 1.0 fails on it even though the design-spec default (1.06) would pass.
        power = (([270] * 40 + [155] * 40) * 45)[:3600]
        hr = [140] * 3600
        result = _check("bike", power, hr, threshold=500, vi_limit=1.0)
        self.assertIn("variable", result["reasons"])

    def test_too_intense_fails_if(self):
        power, hr = _steady_series(power=300)
        result = _check("bike", power, hr, threshold=250)
        self.assertIn("intensity", result["reasons"])

    def test_configured_if_limit_overrides_the_default(self):
        # IF = 300/250 = 1.2, which passes a loosened 1.5 limit even though it fails the default.
        power, hr = _steady_series(power=300)
        result = _check("bike", power, hr, threshold=250, if_limit=1.5)
        self.assertNotIn("intensity", result["reasons"])

    def test_no_threshold_fails_with_its_own_reason_not_also_intensity(self):
        power, hr = _steady_series()
        result = _check("bike", power, hr, threshold=None)
        self.assertIn("no_threshold", result["reasons"])
        self.assertNotIn("intensity", result["reasons"])

    def test_too_short_fails_below_60_minutes(self):
        power, hr = _steady_series(seconds=DECOUPLING_MIN_STEADY_SECONDS - 1)
        result = _check("bike", power, hr, threshold=250)
        self.assertIn("short", result["reasons"])

    def test_configured_min_steady_minutes_overrides_the_default(self):
        # 45 minutes fails the default 60-min floor but passes a loosened 30-min one.
        power, hr = _steady_series(seconds=45 * 60)
        result = _check("bike", power, hr, threshold=250, min_steady_seconds=30 * 60)
        self.assertNotIn("short", result["reasons"])

    def test_insufficient_hr_coverage(self):
        power, hr = _steady_series()
        hr = [None] * 3800 + hr[3800:]  # 200/4000 = 5% coverage
        result = _check("bike", power, hr, threshold=250)
        self.assertIn("hr_coverage", result["reasons"])

    def test_insufficient_power_coverage(self):
        power, hr = _steady_series()
        power = [None] * 3800 + power[3800:]
        result = _check("bike", power, hr, threshold=250)
        self.assertIn("power_coverage", result["reasons"])


class ComputeDecouplingTests(TestCase):
    def _athlete(self):
        return User.objects.create_user(email=f"dec-{id(self)}@example.cc", password="x", name="Athlete")

    def _activity(self, athlete, sport="bike", **kwargs):
        defaults = {"start_date": datetime(2026, 6, 1, 7, 0, tzinfo=UTC), "moving_time": 4000}
        defaults.update(kwargs)
        return Activity.objects.create(athlete=athlete, sport=sport, **defaults)

    def _set_threshold(self, athlete, activity, field="ftp", value=250):
        ThresholdHistory.objects.create(
            athlete=athlete,
            field=field,
            value_numeric=value,
            source_activity=activity,
            effective_from=date(2026, 1, 1),
            current_from=date(2026, 1, 1),
        )

    def test_non_bike_run_sport_gets_sport_reason_and_nothing_computed(self):
        athlete = self._athlete()
        activity = self._activity(athlete, sport="swim")
        power = [200] * 4000
        hr = [140] * 4000
        t = list(range(4000))
        result = compute_decoupling(activity, athlete, power, hr, t, [None] * 4000, [None] * 4000, [None] * 4000)
        self.assertEqual(result["decoupling_reasons"], ["sport"])
        self.assertFalse(result["decoupling_qualified"])
        self.assertIsNone(result["decoupling_pct"])

    def test_no_power_stream_gets_no_power_reason(self):
        athlete = self._athlete()
        activity = self._activity(athlete)
        power = [None] * 4000
        hr = [140] * 4000
        t = list(range(4000))
        result = compute_decoupling(activity, athlete, power, hr, t, [None] * 4000, [None] * 4000, [None] * 4000)
        self.assertEqual(result["decoupling_reasons"], ["no_power"])

    def test_qualified_session_splits_into_two_equal_halves_and_computes_pct(self):
        athlete = self._athlete()
        activity = self._activity(athlete, moving_time=4200)
        self._set_threshold(athlete, activity)
        t = list(range(4200))
        power = [200] * 4200
        # First half HR 128, second half HR higher (131) at the same power - a real drop in
        # efficiency, matching the design spec's own worked example proportions. Steady window
        # is 4200 - 600 warmup = 3600s, exactly the minimum.
        warmup = [140] * 600
        steady = 3600
        mid = 600 + steady // 2
        hr = warmup + [128] * (mid - 600) + [131] * (4200 - mid)
        result = compute_decoupling(activity, athlete, power, hr, t, [None] * 4200, [None] * 4200, [None] * 4200)
        self.assertTrue(result["decoupling_qualified"])
        self.assertEqual(result["decoupling_reasons"], [])
        self.assertAlmostEqual(result["ef_first"], 200 / 128, places=2)
        self.assertAlmostEqual(result["ef_second"], 200 / 131, places=2)
        expected_pct = round((result["ef_first"] - result["ef_second"]) / result["ef_first"] * 100, 1)
        self.assertEqual(result["decoupling_pct"], expected_pct)
        self.assertGreater(result["decoupling_pct"], 0)
        self.assertEqual(len(result["decoupling_halves"]), 2)

    def test_negative_decoupling_is_stored_unchanged(self):
        # Efficiency *improves* in the second half (HR drops at the same power) - a real,
        # allowed outcome per the spec ("negative values are allowed... UI shows them as Good").
        athlete = self._athlete()
        activity = self._activity(athlete, moving_time=4200)
        self._set_threshold(athlete, activity)
        t = list(range(4200))
        power = [200] * 4200
        hr = [140] * 2500 + [130] * 1700
        result = compute_decoupling(activity, athlete, power, hr, t, [None] * 4200, [None] * 4200, [None] * 4200)
        self.assertTrue(result["decoupling_qualified"])
        self.assertLess(result["decoupling_pct"], 0)

    def test_warm_from_air_alone(self):
        # Warm is an OR - either signal elevated is enough, and it doesn't require skin data
        # to be present at all.
        athlete = self._athlete()
        activity = self._activity(athlete, sport="run")
        self._set_threshold(athlete, activity, field="critical_run_power")
        t = list(range(4000))
        power = [200] * 4000
        hr = [140] * 4000
        air = [26.0] * 4000
        result = compute_decoupling(activity, athlete, power, hr, t, air, [None] * 4000, [None] * 4000)
        self.assertTrue(result["decoupling_warm"])
        self.assertFalse(result["decoupling_hot"])
        self.assertAlmostEqual(result["decoupling_avg_temp"], 26.0, places=1)
        self.assertIsNone(result["decoupling_avg_core"])

    def test_warm_from_skin_alone(self):
        athlete = self._athlete()
        activity = self._activity(athlete)
        self._set_threshold(athlete, activity)
        t = list(range(4000))
        power = [200] * 4000
        hr = [140] * 4000
        skin = [34.0] * 4000
        result = compute_decoupling(activity, athlete, power, hr, t, [None] * 4000, [None] * 4000, skin)
        self.assertTrue(result["decoupling_warm"])
        self.assertFalse(result["decoupling_hot"])
        self.assertAlmostEqual(result["decoupling_avg_skin"], 34.0, places=1)

    def test_hot_requires_both_air_and_skin_elevated(self):
        athlete = self._athlete()
        activity = self._activity(athlete)
        self._set_threshold(athlete, activity)
        t = list(range(4000))
        power = [200] * 4000
        hr = [140] * 4000
        air = [31.0] * 4000
        skin = [35.0] * 4000
        result = compute_decoupling(activity, athlete, power, hr, t, air, [None] * 4000, skin)
        self.assertTrue(result["decoupling_warm"])
        self.assertTrue(result["decoupling_hot"])

    def test_high_air_alone_is_warm_but_not_hot(self):
        athlete = self._athlete()
        activity = self._activity(athlete)
        self._set_threshold(athlete, activity)
        t = list(range(4000))
        power = [200] * 4000
        hr = [140] * 4000
        air = [31.0] * 4000
        skin = [30.0] * 4000  # below both the warm (33) and hot (34) skin floors
        result = compute_decoupling(activity, athlete, power, hr, t, air, [None] * 4000, skin)
        self.assertTrue(result["decoupling_warm"])
        self.assertFalse(result["decoupling_hot"])

    def test_high_skin_alone_is_warm_but_not_hot(self):
        athlete = self._athlete()
        activity = self._activity(athlete)
        self._set_threshold(athlete, activity)
        t = list(range(4000))
        power = [200] * 4000
        hr = [140] * 4000
        air = [20.0] * 4000  # below both the warm (25) and hot (30) air floors
        skin = [35.0] * 4000
        result = compute_decoupling(activity, athlete, power, hr, t, air, [None] * 4000, skin)
        self.assertTrue(result["decoupling_warm"])
        self.assertFalse(result["decoupling_hot"])

    def test_high_core_alone_is_neither_warm_nor_hot(self):
        # Core temp no longer gates either flag - a long steady effort drives it up from
        # sustained exertion alone, even on a cool day, so it's informational only
        # (decoupling_avg_core). This is the exact real-world false positive that prompted the
        # switch to air/skin: a 3h run, ~16 C air, core drifting to ~38.1 C, skin staying
        # ~31.6 C (below even the 33 C warm-skin floor) since the body was shedding heat into
        # cool air just fine.
        athlete = self._athlete()
        activity = self._activity(athlete, sport="run")
        self._set_threshold(athlete, activity, field="critical_run_power")
        t = list(range(4000))
        power = [200] * 4000
        hr = [140] * 4000
        air = [16.2] * 4000
        core = [38.1] * 4000
        skin = [31.6] * 4000
        result = compute_decoupling(activity, athlete, power, hr, t, air, core, skin)
        self.assertFalse(result["decoupling_warm"])
        self.assertFalse(result["decoupling_hot"])
        self.assertAlmostEqual(result["decoupling_avg_core"], 38.1, places=1)

    def test_not_warm_below_all_thresholds(self):
        athlete = self._athlete()
        activity = self._activity(athlete)
        self._set_threshold(athlete, activity)
        t = list(range(4000))
        power = [200] * 4000
        hr = [140] * 4000
        air = [18.0] * 4000
        skin = [28.0] * 4000
        result = compute_decoupling(activity, athlete, power, hr, t, air, [None] * 4000, skin)
        self.assertFalse(result["decoupling_warm"])
        self.assertFalse(result["decoupling_hot"])

    def test_athletes_own_configured_heat_thresholds_are_used_not_the_defaults(self):
        # A session that's cool under the design-spec defaults (air 27 C < 30 C hot floor)
        # trips "hot" once the athlete loosens their own hot-air floor to 26 C - confirms
        # compute_decoupling actually reads User.decoupling_hot_air_temp/_skin_temp rather than
        # the module-level defaults.
        athlete = self._athlete()
        athlete.decoupling_hot_air_temp = 26.0
        athlete.save(update_fields=["decoupling_hot_air_temp"])
        activity = self._activity(athlete)
        self._set_threshold(athlete, activity)
        t = list(range(4000))
        power = [200] * 4000
        hr = [140] * 4000
        air = [27.0] * 4000
        skin = [35.0] * 4000
        result = compute_decoupling(activity, athlete, power, hr, t, air, [None] * 4000, skin)
        self.assertTrue(result["decoupling_hot"])

    def test_warm_and_vi_if_still_populated_when_not_qualified(self):
        # Not-qualified (too intense) sessions still show real VI/IF/steady_seconds/warm/hot
        # numbers in the checks row - only the ef/pct fields are withheld.
        athlete = self._athlete()
        activity = self._activity(athlete)
        self._set_threshold(athlete, activity, value=100)  # IF = 200/100 = 2.0, fails
        t = list(range(4000))
        power = [200] * 4000
        hr = [140] * 4000
        air = [26.0] * 4000
        result = compute_decoupling(activity, athlete, power, hr, t, air, [None] * 4000, [None] * 4000)
        self.assertFalse(result["decoupling_qualified"])
        self.assertIn("intensity", result["decoupling_reasons"])
        self.assertIsNotNone(result["decoupling_vi"])
        self.assertIsNotNone(result["decoupling_if"])
        self.assertEqual(result["steady_seconds"], 3400)
        self.assertTrue(result["decoupling_warm"])
        self.assertFalse(result["decoupling_hot"])
        self.assertIsNone(result["decoupling_pct"])
        self.assertEqual(result["decoupling_halves"], [])

    def test_athletes_own_configured_thresholds_are_used_not_the_defaults(self):
        # A session that's a clean pass under the design-spec defaults (IF 0.8 <= 0.85) fails
        # once the athlete tightens their own IF limit to 0.7 - confirms compute_decoupling
        # actually reads User.decoupling_if_limit rather than the module-level default.
        athlete = self._athlete()
        athlete.decoupling_if_limit = 0.7
        athlete.save(update_fields=["decoupling_if_limit"])
        activity = self._activity(athlete, moving_time=4200)
        self._set_threshold(athlete, activity, value=250)
        t = list(range(4200))
        power = [200] * 4200
        hr = [140] * 4200
        result = compute_decoupling(activity, athlete, power, hr, t, [None] * 4200, [None] * 4200, [None] * 4200)
        self.assertFalse(result["decoupling_qualified"])
        self.assertIn("intensity", result["decoupling_reasons"])


class ComputeDurabilityRowsTests(SimpleTestCase):
    def test_non_bike_run_sport_returns_no_rows(self):
        activity = Activity(sport="swim")
        rows = compute_durability_rows(activity, User(), [200] * 4000, list(range(4000)))
        self.assertEqual(rows, [])

    def test_low_power_coverage_returns_no_rows(self):
        activity = Activity(sport="bike")
        power = [None] * 3900 + [200] * 100
        rows = compute_durability_rows(activity, User(), power, list(range(4000)))
        self.assertEqual(rows, [])

    def test_threshold_zero_matches_the_plain_mean_max(self):
        activity = Activity(sport="bike")
        power = [200 + (i % 50) for i in range(4000)]
        t = list(range(4000))
        rows = compute_durability_rows(activity, User(), power, t)
        fresh_20min = next(r for r in rows if r["threshold"] == 0 and r["window_s"] == 1200)
        expected = round(_sliding_window_best_avg(power, 1200))
        self.assertEqual(fresh_20min["power"], expected)

    def test_threshold_reached_too_close_to_the_end_writes_no_row_for_that_cell(self):
        # ~3000 kJ reached with only 100s of data left - not enough for the 300s (5-min)
        # window, let alone 1200/3600 - but earlier thresholds with plenty of remaining data
        # still produce rows.
        activity = Activity(sport="bike")
        power = [1000] * 3100 + [200] * 100  # ~3100 kJ by t=3100, then a short tail
        t = list(range(3200))
        rows = compute_durability_rows(activity, User(), power, t)
        cells = {(r["threshold"], r["window_s"]) for r in rows}
        self.assertNotIn((3000, 300), cells)
        self.assertIn((1000, 300), cells)

    def test_run_uses_minutes_basis_and_bike_uses_kj(self):
        # 1000W steady over 4000s = 4000 kJ total, reaching every bike threshold (1000/2000/
        # 3000 kJ) with at least the 5-min window's worth of data still remaining afterwards.
        bike_t = list(range(4000))
        bike_power = [1000] * 4000
        bike_rows = compute_durability_rows(Activity(sport="bike"), User(), bike_power, bike_t)
        self.assertTrue(all(r["basis"] == "kj" for r in bike_rows))
        self.assertEqual({r["threshold"] for r in bike_rows}, {0, 1000, 2000, 3000})

        # Run's thresholds are minutes of moving time, independent of power - 90 min + a 5-min
        # window needs at least 95*60 samples.
        run_t = list(range(95 * 60))
        run_power = [250] * len(run_t)
        run_rows = compute_durability_rows(Activity(sport="run"), User(), run_power, run_t)
        self.assertTrue(all(r["basis"] == "minutes" for r in run_rows))
        self.assertEqual({r["threshold"] for r in run_rows}, {0, 60, 90})
