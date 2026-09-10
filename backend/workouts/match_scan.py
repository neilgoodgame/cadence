"""Scans a workout's athlete's own unmatched, same-sport activities for likely matches, by
correlating (Pearson) each candidate's actual per-second power stream against the workout's
planned %FTP-vs-time curve. See the `WorkoutMatchScan` model's docstring for why this needs to
be a background job rather than a synchronous request.

v1 only supports power-target, duration-based workouts (every flattened leaf step must have
`target_type="power"` and `end_type="time"`) - pace-target workouts read too noisily off
GPS/treadmill speed to trust at the same confidence threshold validated for power (no forced
compliance mechanism the way ERG mode holds power steady), so they're intentionally out of
scope for now. Follow-up: validate against a real pace-based workout before adding support.
"""

import math
from typing import TYPE_CHECKING

from activities.models import Activity
from athletes.zones import reference_for

from .calculations import _DEFAULT_POWER_REFERENCE, flatten_persisted_steps

if TYPE_CHECKING:
    from .models import Workout, WorkoutMatchScan

# Validated this session against real historical data: three independent matches all held
# r >= 0.87 with a duration diff of 0-12s, while widening the window to +/-60s only ever added
# noise-floor candidates (r <= ~0.52), never a false positive - ranking is by correlation, not
# by inclusion, so a generous width costs nothing but a few extra rows to evaluate.
DURATION_TOLERANCE_SECONDS = 60


def scannability_error(workout: "Workout") -> str | None:
    """`None` if `workout` can be scanned for matches; otherwise the reason it can't, suitable
    for a 400 response. See the module docstring for why v1 is power/duration-only."""
    flattened = flatten_persisted_steps(workout)
    if not flattened:
        return "This workout has no steps to scan against."
    for step, _ in flattened:
        if step.target_type != "power":
            return "Only power-target workouts can be scanned for matches right now."
        if step.end_type != "time" or not step.duration:
            return "Only duration-based steps (not distance- or manual-ended) can be scanned for matches right now."
    return None


def _power_reference(workout: "Workout") -> float:
    zone_type = "bike_power" if workout.sport == "bike" else "run_power"
    return reference_for(workout.created_by, zone_type) or _DEFAULT_POWER_REFERENCE


def build_expected_curve(workout: "Workout") -> list[tuple[int, int, float]]:
    """Cumulative `(start_s, end_s, intensity_pct)` segments for `workout`'s flattened steps -
    `scannability_error` must already have confirmed every step is power/duration-based.
    `intensity_pct` is always expressed as %FTP: a `watts`-unit step is converted using the
    athlete's current bike/run power reference (falling back to the same default
    `calculations.normalize_power_units` uses when the athlete hasn't set one). The exact
    reference value barely matters for the correlation itself - Pearson is invariant to any
    single consistent rescale of the whole curve - it only affects the informational
    `implied_ftp` reported back per candidate.
    """
    reference = _power_reference(workout)
    curve: list[tuple[int, int, float]] = []
    offset = 0
    for step, _ in flatten_persisted_steps(workout):
        low = step.target_low if step.target_low is not None else 0.0
        high = step.target_high if step.target_high is not None else low
        mid = (low + high) / 2
        pct = (mid / reference) * 100 if step.power_unit == "watts" else mid
        curve.append((offset, offset + step.duration, pct))
        offset += step.duration
    return curve


def _sample_expected_at(curve: list[tuple[int, int, float]], t: int) -> float | None:
    for start, end, pct in curve:
        if start <= t < end:
            return pct
    # t landing exactly on the plan's own total duration - the closing boundary of the last
    # segment, which the `t < end` check above always excludes.
    if curve and t == curve[-1][1]:
        return curve[-1][2]
    return None


def pearson(xs: list[float], ys: list[float]) -> float | None:
    """`None` when there are too few paired samples, or either series is constant (a flat
    target or a dead-flat power reading can't be correlated - not an error, just undefined)."""
    n = len(xs)
    if n < 3:
        return None
    mean_x = sum(xs) / n
    mean_y = sum(ys) / n
    cov = sum((x - mean_x) * (y - mean_y) for x, y in zip(xs, ys, strict=True))
    var_x = sum((x - mean_x) ** 2 for x in xs)
    var_y = sum((y - mean_y) ** 2 for y in ys)
    if var_x == 0 or var_y == 0:
        return None
    return cov / math.sqrt(var_x * var_y)


def correlate_activity(
    curve: list[tuple[int, int, float]], activity: Activity
) -> tuple[float, float, int | None] | None:
    """Returns `(correlation, coverage, implied_ftp)` for `activity` against `curve`, or `None`
    if there's no usable overlap (no records, no power data, or nothing falls inside the
    workout's planned duration)."""
    records = list(activity.records.order_by("t").values_list("t", "power"))
    if not records:
        return None
    start_t = records[0][0]
    pairs = []
    for t, power in records:
        if power is None:
            continue
        expected = _sample_expected_at(curve, t - start_t)
        if expected is not None:
            pairs.append((expected, power))
    if not pairs:
        return None

    xs = [p[0] for p in pairs]
    ys = [p[1] for p in pairs]
    r = pearson(xs, ys)
    if r is None:
        return None

    n = len(pairs)
    mean_x = sum(xs) / n
    mean_y = sum(ys) / n
    var_x = sum((x - mean_x) ** 2 for x in xs)
    implied_ftp = None
    if var_x > 0:
        cov = sum((x - mean_x) * (y - mean_y) for x, y in zip(xs, ys, strict=True))
        implied_ftp = round((cov / var_x) * 100)

    coverage = len(pairs) / len(records)
    return round(r, 4), round(coverage, 4), implied_ftp


def run_match_scan(scan: "WorkoutMatchScan") -> None:
    """Orchestrates a full scan: candidate selection (same athlete, same sport as the workout,
    `workout_id IS NULL` - a real query against the whole `Activity` table, so it doesn't share
    the truncated-history gap the MCP-based prototype had), the duration pre-filter, then a
    correlation pass per surviving candidate. Persists a `WorkoutMatchScanCandidate` row for
    every candidate that passed the duration filter, however low its score, so the full ranked
    list stays inspectable. Caller is expected to have already set `status="processing"`.
    """
    from .models import WorkoutMatchScanCandidate  # deferred: avoid a models.py <-> here import cycle

    workout = scan.workout
    curve = build_expected_curve(workout)
    total_planned_duration = curve[-1][1] if curve else 0

    all_candidates = Activity.objects.filter(
        athlete_id=workout.created_by_id, sport=workout.sport, workout__isnull=True
    )
    candidates = [
        a for a in all_candidates if abs(a.moving_time - total_planned_duration) <= DURATION_TOLERANCE_SECONDS
    ]
    scan.total_candidates = len(candidates)
    scan.save(update_fields=["total_candidates"])

    rows = []
    for i, activity in enumerate(candidates, start=1):
        result = correlate_activity(curve, activity)
        if result is not None:
            r, coverage, implied_ftp = result
            rows.append(
                WorkoutMatchScanCandidate(
                    scan=scan,
                    activity=activity,
                    correlation=r,
                    duration_diff_seconds=abs(activity.moving_time - total_planned_duration),
                    coverage=coverage,
                    implied_ftp=implied_ftp,
                )
            )
        scan.processed_candidates = i
        scan.save(update_fields=["processed_candidates"])

    WorkoutMatchScanCandidate.objects.bulk_create(rows)
