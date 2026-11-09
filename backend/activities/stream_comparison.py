"""Issue #503 (phase 4): compares two already-stored streams for the same field on the same
activity (e.g. a wrist-HR primary against an attached chest-strap source) and reports agreement
statistics. Pure computation only: no database access - callers resolve both sides to a
``{t: value}`` series first (see ``activity_source_service.load_field_series``) and pass them in.
Unlike merge_alignment, both series here already share one activity's own ``t`` clock - any
cross-recording offset was already resolved before either stream was stored - so the lag this
module searches for is residual/physiological (e.g. wrist HR trailing a strap by a few seconds),
not a clock mismatch, hence the much smaller search radius. Mirrors backend_java's
StreamComparisonCalculator.java - keep the two in sync.
"""

from dataclasses import dataclass
from typing import NamedTuple

# Below this many overlapping (non-null, paired) samples, none of these statistics are
# trustworthy enough to report - same floor merge_alignment uses, for the same reason.
MIN_OVERLAP_SAMPLES = 30
# How far to search for a residual lag, in seconds, each direction - generously covers known
# wrist-HR reporting lag (a handful of seconds) without merge_alignment's +/-120s, which is for
# two independently-clocked recordings, not two streams already sharing this activity's own
# timeline.
LAG_SEARCH_RADIUS_S = 30


@dataclass(frozen=True)
class ComparisonResult:
    overlap_seconds: int
    bias: float
    mae: float
    rmse: float
    pearson_r: float
    loa_lower: float
    loa_upper: float
    within_tolerance_pct: float


@dataclass(frozen=True)
class LagResult:
    lag_seconds: int
    correlation: float
    overlap_seconds: int


@dataclass(frozen=True)
class PowerFit:
    scale: float
    offset: float
    ratio_of_means: float


class ZoneBucketResult(NamedTuple):
    zone_name: str
    sample_count: int
    bias: float | None
    mae: float | None
    rmse: float | None


class Tolerance(NamedTuple):
    """A tolerance spec for "% of paired seconds within X" - ``absolute`` (e.g. 5 bpm for
    heartrate) or a percentage of series A's own value (e.g. 3% for power/cadence), never
    both."""

    absolute: float | None
    relative_pct: float | None

    @staticmethod
    def of_absolute(value: float) -> "Tolerance":
        return Tolerance(value, None)

    @staticmethod
    def of_relative_pct(pct: float) -> "Tolerance":
        return Tolerance(None, pct)


def _pearson(pairs: list[tuple[float, float]]) -> float:
    n = len(pairs)
    sum_x = sum(x for x, _ in pairs)
    sum_y = sum(y for _, y in pairs)
    sum_xy = sum(x * y for x, y in pairs)
    sum_x2 = sum(x * x for x, _ in pairs)
    sum_y2 = sum(y * y for _, y in pairs)
    denom = ((n * sum_x2 - sum_x**2) * (n * sum_y2 - sum_y**2)) ** 0.5
    return (n * sum_xy - sum_x * sum_y) / denom if denom else 0.0


def _pair(series_a: dict[int, int], series_b: dict[int, int]) -> list[tuple[float, float]]:
    return [(float(series_a[t]), float(series_b[t])) for t in sorted(series_a) if t in series_b]


def compare(series_a: dict[int, int], series_b: dict[int, int], tolerance: Tolerance) -> ComparisonResult | None:
    """Pairs series_a/series_b on shared t and computes agreement statistics over the overlap.
    Returns None if the overlap has fewer than MIN_OVERLAP_SAMPLES paired seconds - callers
    should reject the request rather than report a statistic computed on too little data."""
    pairs = _pair(series_a, series_b)
    n = len(pairs)
    if n < MIN_OVERLAP_SAMPLES:
        return None

    diffs = [b - a for a, b in pairs]
    bias = sum(diffs) / n
    mae = sum(abs(d) for d in diffs) / n
    rmse = (sum(d * d for d in diffs) / n) ** 0.5
    stddev = (sum((d - bias) ** 2 for d in diffs) / n) ** 0.5
    pearson_r = _pearson(pairs)

    allowed = [
        tolerance.absolute if tolerance.absolute is not None else abs(a) * tolerance.relative_pct / 100.0
        for a, _ in pairs
    ]
    within_tolerance = sum(1 for diff, limit in zip(diffs, allowed, strict=True) if abs(diff) <= limit)

    return ComparisonResult(
        overlap_seconds=n,
        bias=bias,
        mae=mae,
        rmse=rmse,
        pearson_r=pearson_r,
        loa_lower=bias - 1.96 * stddev,
        loa_upper=bias + 1.96 * stddev,
        within_tolerance_pct=100.0 * within_tolerance / n,
    )


def find_lag(series_a: dict[int, int], series_b: dict[int, int]) -> LagResult | None:
    """Searches lags in [-LAG_SEARCH_RADIUS_S, +LAG_SEARCH_RADIUS_S]; for each, pairs
    series_a[t - lag] with series_b[t] wherever both exist and scores the pairing by Pearson
    correlation - a positive lag means series_b's value at time t best matches series_a's value
    from `lag` seconds earlier, i.e. b is trailing a by `lag` seconds (b reports late, e.g. wrist
    HR behind a chest strap). Returns None if no candidate lag had enough overlap to score."""
    best_lag, best_score, best_overlap = 0, -2.0, 0
    for lag in range(-LAG_SEARCH_RADIUS_S, LAG_SEARCH_RADIUS_S + 1):
        pairs = [(series_a[t - lag], series_b[t]) for t in series_b if (t - lag) in series_a]
        if len(pairs) < MIN_OVERLAP_SAMPLES:
            continue
        score = _pearson(pairs)
        if score > best_score:
            best_lag, best_score, best_overlap = lag, score, len(pairs)
    return None if best_overlap == 0 else LagResult(best_lag, best_score, best_overlap)


def fit_power_scale_offset(series_a: dict[int, int], series_b: dict[int, int]) -> PowerFit:
    """Ordinary least-squares fit of b = scale*a + offset over the paired, overlapping samples,
    plus the simple ratio of the two series' means - power-field only, the drivetrain-loss
    estimate between two power meters."""
    pairs = _pair(series_a, series_b)
    n = len(pairs)
    sum_x = sum(a for a, _ in pairs)
    sum_y = sum(b for _, b in pairs)
    sum_xy = sum(a * b for a, b in pairs)
    sum_x2 = sum(a * a for a, _ in pairs)
    denom = n * sum_x2 - sum_x**2
    scale = (n * sum_xy - sum_x * sum_y) / denom if denom else 1.0
    offset = (sum_y - scale * sum_x) / n
    mean_a = sum_x / n
    mean_b = sum_y / n
    return PowerFit(scale=scale, offset=offset, ratio_of_means=mean_b / mean_a if mean_a else 0.0)


def zone_breakdown(
    series_a: dict[int, int], series_b: dict[int, int], zones: list[dict], reference: float
) -> list[ZoneBucketResult]:
    """Buckets the paired overlap by series A's value as a %-of-reference, walking the zone
    bands top-down exactly as the frontend's zones.ts's bucketIntoZones does, then reports
    per-zone bias/MAE/RMSE instead of just time-in-zone. A zone with no samples still appears,
    with null stats, matching bucketIntoZones' "report nothing silently" behavior rather than
    omitting the bucket."""
    buckets: list[list[tuple[float, float]]] = [[] for _ in zones]
    for a, b in _pair(series_a, series_b):
        pct = (a / reference) * 100.0 if reference else 0.0
        for i in range(len(zones) - 1, -1, -1):
            if pct >= zones[i]["low_pct"] or i == 0:
                buckets[i].append((a, b))
                break

    results = []
    for zone, bucket in zip(zones, buckets, strict=True):
        if not bucket:
            results.append(ZoneBucketResult(zone["name"], 0, None, None, None))
            continue
        diffs = [b - a for a, b in bucket]
        n = len(bucket)
        results.append(
            ZoneBucketResult(
                zone["name"],
                n,
                sum(diffs) / n,
                sum(abs(d) for d in diffs) / n,
                (sum(d * d for d in diffs) / n) ** 0.5,
            )
        )
    return results
