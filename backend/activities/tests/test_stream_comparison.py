"""Issue #503 (phase 4): activities.stream_comparison is pure computation (no DB), so these are
plain functions, not a TestCase - conftest.py's auto-tagging puts them in the `unit` bucket.
Mirrors backend_java's StreamComparisonCalculatorTest."""

import random

import pytest

from ..stream_comparison import Tolerance, compare, find_lag, fit_power_scale_offset, zone_breakdown


# Same non-periodic-signal convention test_merge_alignment.py's own profile() uses for its lag
# search test - a periodic signal would let a wrong-but-aliased lag score perfectly too.
def _profile(t: int) -> int:
    return round(80.0 + 20.0 * random.Random(t * 100_000).uniform(-1, 1))


def _series(n: int, fn) -> dict[int, int]:
    return {t: fn(t) for t in range(n)}


def test_compare_computes_bias_mae_rmse_and_pearson_on_a_constant_offset():
    a = _series(40, lambda t: 140 + t)
    b = _series(40, lambda t: 150 + t)  # a constant +10 offset, perfectly correlated

    result = compare(a, b, Tolerance.of_absolute(5.0))

    assert result is not None
    assert result.overlap_seconds == 40
    assert result.bias == pytest.approx(10.0, abs=1e-3)
    assert result.mae == pytest.approx(10.0, abs=1e-3)
    assert result.rmse == pytest.approx(10.0, abs=1e-3)
    assert result.pearson_r == pytest.approx(1.0, abs=1e-3)
    assert result.loa_lower == pytest.approx(10.0, abs=1e-3)
    assert result.loa_upper == pytest.approx(10.0, abs=1e-3)
    assert result.within_tolerance_pct == pytest.approx(0.0, abs=1e-3)  # 10bpm diff, 5bpm tolerance


def test_compare_returns_none_below_the_minimum_overlap():
    a = _series(10, lambda t: 140 + t)
    b = _series(10, lambda t: 150 + t)

    assert compare(a, b, Tolerance.of_absolute(5.0)) is None


def test_compare_with_a_relative_tolerance_measures_against_series_as_own_value():
    a = _series(40, lambda t: 200)  # constant, so 3% tolerance is a fixed 6
    b = _series(40, lambda t: 205)  # 5 off - within 3% of 200 (6)

    result = compare(a, b, Tolerance.of_relative_pct(3.0))

    assert result.within_tolerance_pct == pytest.approx(100.0, abs=1e-3)


def test_find_lag_recovers_a_known_injected_shift():
    a = _series(120, _profile)
    # b lags a by 7 seconds: b[t] == a[t-7], i.e. b's own series evaluated at (t-7).
    b = _series(120, lambda t: _profile(t - 7))

    lag = find_lag(a, b)

    assert lag is not None
    assert lag.lag_seconds == 7
    assert lag.correlation > 0.99


def test_fit_power_scale_offset_recovers_a_known_linear_relationship():
    a = _series(40, lambda t: 200 + t)
    b = _series(40, lambda t: round(1.1 * (200 + t) + 5))  # scale 1.1, offset 5

    fit = fit_power_scale_offset(a, b)

    assert fit.scale == pytest.approx(1.1, abs=0.01)
    assert fit.offset == pytest.approx(5.0, abs=1.0)
    assert fit.ratio_of_means > 1.0


def test_zone_breakdown_buckets_pairs_by_series_a_as_percent_of_reference_top_down():
    # Two bands: Z1 [0,60), Z2 [60,150) - a's values range 50..109 against reference 100, so
    # values below 60% of 100 (i.e. a < 60) fall in Z1, the rest in Z2.
    zones = [{"name": "Z1", "low_pct": 0, "high_pct": 59}, {"name": "Z2", "low_pct": 60, "high_pct": 150}]
    a = _series(60, lambda t: 50 + t)  # 50..109
    b = _series(60, lambda t: 50 + t + 10)  # a constant +10 over a

    buckets = zone_breakdown(a, b, zones, 100.0)

    assert len(buckets) == 2
    z1, z2 = buckets
    # a<60 for t in [0,10) -> 10 samples in Z1; the remaining 50 in Z2.
    assert z1.sample_count == 10
    assert z2.sample_count == 50
    assert z1.bias == pytest.approx(10.0, abs=1e-3)
    assert z2.bias == pytest.approx(10.0, abs=1e-3)


def test_zone_breakdown_reports_an_empty_bucket_with_null_stats_rather_than_omitting_it():
    zones = [{"name": "Z1", "low_pct": 0, "high_pct": 50}, {"name": "Z2", "low_pct": 51, "high_pct": 150}]
    a = _series(40, lambda t: 200 + t)  # always >> 50% of reference 10 - all Z2
    b = _series(40, lambda t: 205 + t)

    buckets = zone_breakdown(a, b, zones, 10.0)

    z1 = buckets[0]
    assert z1.sample_count == 0
    assert z1.bias is None
    assert z1.mae is None
    assert z1.rmse is None
