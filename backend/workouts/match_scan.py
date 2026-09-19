"""Scans a workout's athlete's own unmatched, same-sport activities for likely matches, by
correlating (Pearson) each candidate's actual per-second power stream against the workout's
planned %FTP-vs-time curve. See the `WorkoutMatchScan` model's docstring for why this needs to
be a background job rather than a synchronous request.

v1 only supports power-target workouts (every flattened leaf step must have `target_type=
"power"`) - pace-target workouts read too noisily off GPS/treadmill speed to trust at the same
confidence threshold validated for power (no forced compliance mechanism the way ERG mode holds
power steady), so they're intentionally out of scope for now. Follow-up: validate against a
real pace-based workout before adding support.

`time`-, `distance`-, and `manual`-ended steps are all supported, but need different strategies.
A `time`-ended step's boundary is the same for every candidate (it's baked into the plan), so
`build_expected_curve`/`correlate_records` build one reusable (start_s, end_s, intensity_pct)
curve per workout and correlate many candidates against it. A `distance`-ended step's boundary
depends on how fast *this specific* candidate actually covered that distance, and a
`manual`-ended step's boundary depends on whenever *this specific* candidate's athlete pressed
lap - neither can be predicted from the plan alone, so there's no single curve valid for every
candidate. A workout with any such step instead walks each candidate's own record stream
directly (`correlate_records_by_step_boundary`), assigning each record to whichever step it
falls in - for `manual`, by consulting that candidate's own real device laps (see that
function's docstring for the full reasoning) rather than any plan value at all, since a manual
step's real length is *defined* as "however long until the athlete pressed lap", not something
the plan ever stores. Manual-ended steps are only scannable when paired with `target_type=
"open"` (no target to correlate against anyway) - an established combination (see workouts/
inference.py, which produces exactly this when a real device lap has no power/HR signal); a
manual step with a real target is intentionally still rejected as unvalidated.

Validated this session against a real distance-ended run-power workout (4 distance blocks at
80/90/97/105% CP with timed recoveries) and its real matched activity: the boundary walk itself
tracked essentially exactly (each block's real distance covered within ~1% of its planned
distance, power rising monotonically block-to-block with the target). The resulting correlation
(~0.55-0.65) reads noticeably lower than bike power's validated ~0.87 baseline though - not a
bug in the boundary logic, but a real property of running power meters: there's a large fixed
biomechanical cost to running at any pace (unlike a bike, which can freewheel to near-zero
power), so an easy recovery jog's actual power compresses far less below a hard interval's than
the %-of-threshold target design assumes. Still clearly useful for *ranking* candidates (an
unrelated activity scores far lower still), just don't expect run-power distance-interval scans
to read as high in absolute terms as bike's.
"""

import math
from typing import TYPE_CHECKING

from activities.models import Activity
from athletes.zones import reference_for

from .calculations import _DEFAULT_POWER_REFERENCE, flatten_persisted_steps

if TYPE_CHECKING:
    from .models import Workout, WorkoutMatchScan, WorkoutStep

# Validated this session against real historical data: three independent matches all held
# r >= 0.87 with a duration diff of 0-12s, while widening the window to +/-60s only ever added
# noise-floor candidates (r <= ~0.52), never a false positive - ranking is by correlation, not
# by inclusion, so a generous width costs nothing but a few extra rows to evaluate.
DURATION_TOLERANCE_SECONDS = 60


def scannability_error(workout: "Workout", excluded_kinds: frozenset[str] = frozenset()) -> str | None:
    """`None` if `workout` can be scanned for matches; otherwise the reason it can't, suitable
    for a 400 response. See the module docstring for why v1 is power-only, and for why a
    distance-ended or manual+open step is still scannable despite having no fixed time boundary.

    `excluded_kinds` (leaf `kind` values, e.g. `{"warmup", "cool"}`) are skipped entirely before
    validation - a step that won't be used to build the curve shouldn't be able to disqualify
    the whole workout (e.g. a distance-ended cooldown the caller has chosen to exclude anyway).
    """
    flattened = [(step, idx) for step, idx in flatten_persisted_steps(workout) if step.kind not in excluded_kinds]
    if not flattened:
        return "This workout has no steps to scan against."
    for step, _ in flattened:
        if step.end_type == "manual":
            # A manual-ended step's real boundary is whatever the athlete's own device lap
            # says, never something the plan can predict - see correlate_records_by_step_
            # boundary for how that's resolved per candidate. That's only harmless when there's
            # no target to correlate against anyway (target_type="open" - "lap whenever, no
            # target" is an established combination: see workouts/inference.py, which produces
            # exactly this when a real device lap has no power/HR signal to characterize it). A
            # manual step WITH a real target (e.g. "hold zone 3 until you decide to stop") is
            # intentionally still rejected - correlating a real target against a boundary this
            # loosely inferred hasn't been validated against a real workout of that shape yet.
            if step.target_type != "open":
                return "A manual-ended step needs an open target to be scanned right now."
            continue
        if step.target_type != "power":
            return "Only power-target workouts can be scanned for matches right now."
        if step.end_type == "time" and not step.duration:
            return "Every time-ended step needs a duration to be scanned."
        if step.end_type == "distance" and not step.distance:
            return "Every distance-ended step needs a distance to be scanned."
    return None


def _needs_step_boundary_walk(flattened: list[tuple["WorkoutStep", int | None]]) -> bool:
    """Whether `flattened` contains any step whose boundary can't be placed on a single
    time-based curve reusable across every candidate - a distance-ended step (its real time
    window depends on how fast *this* candidate covered that distance) or a manual-ended one
    (its real time window depends on whenever *this* candidate's athlete pressed lap) - see the
    module docstring and `correlate_records_by_step_boundary`."""
    return any(step.end_type in ("distance", "manual") for step, _ in flattened)


def _power_reference(workout: "Workout") -> float:
    zone_type = "bike_power" if workout.sport == "bike" else "run_power"
    return reference_for(workout.created_by, zone_type) or _DEFAULT_POWER_REFERENCE


def _step_intensity_pct(step: "WorkoutStep", reference: float) -> float:
    """A single step's expected intensity, always expressed as %FTP: a `watts`-unit step is
    converted using the athlete's current bike/run power reference (falling back to the same
    default `calculations.normalize_power_units` uses when the athlete hasn't set one). The
    exact reference value barely matters for the correlation itself - Pearson is invariant to
    any single consistent rescale of the whole curve - it only affects the informational
    `implied_ftp` reported back per candidate. Shared between `build_expected_curve` (every step
    time-ended, one curve reusable across candidates) and `correlate_records_by_step_boundary`
    (any step distance-ended, walked fresh per candidate) - see the module docstring."""
    low = step.target_low if step.target_low is not None else 0.0
    high = step.target_high if step.target_high is not None else low
    mid = (low + high) / 2
    return (mid / reference) * 100 if step.power_unit == "watts" else mid


def build_expected_curve(
    workout: "Workout", reference: float | None = None, excluded_kinds: frozenset[str] = frozenset()
) -> list[tuple[int, int, float]]:
    """Cumulative `(start_s, end_s, intensity_pct)` segments for `workout`'s flattened steps -
    callers must already have confirmed every step is time-ended (`_needs_step_boundary_walk` is
    `False`); a distance- or manual-ended step has no fixed time boundary to place in this curve
    at all - see `correlate_records_by_step_boundary` and the module docstring for that case
    instead.

    `reference` lets a caller ranking many workouts for the same athlete (see
    `rank_workouts_for_activity`) compute the zone lookup once and reuse it, instead of
    repeating an identical lookup per candidate - left `None` to compute it here as before.

    `excluded_kinds` (leaf `kind` values) skip emitting a curve segment for that step, but the
    running `offset` still advances past its duration - later segments keep their correct
    absolute position in the workout's timeline (the activity recording still covers the
    excluded phase in real time; `_sample_expected_at` already returns `None` for any `t` no
    segment covers, so no other change is needed to make those samples fall out of the
    correlation).
    """
    if reference is None:
        reference = _power_reference(workout)
    curve: list[tuple[int, int, float]] = []
    offset = 0
    for step, _ in flatten_persisted_steps(workout):
        if step.kind not in excluded_kinds:
            curve.append((offset, offset + step.duration, _step_intensity_pct(step, reference)))
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


def _correlate_pairs(pairs: list[tuple[float, int]], total_records: int) -> tuple[float, float, int | None] | None:
    """Shared tail of `correlate_records` and `correlate_records_by_step_boundary`: turns a list
    of (expected_pct, actual_power) pairs into `(correlation, coverage, implied_ftp)`, or `None`
    if there's nothing to correlate. `implied_ftp` is a regression-slope-derived value,
    informational only, never used for ranking."""
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

    coverage = len(pairs) / total_records
    return round(r, 4), round(coverage, 4), implied_ftp


def correlate_records(
    curve: list[tuple[int, int, float]], records: list[tuple[int, int | None]]
) -> tuple[float, float, int | None] | None:
    """Returns `(correlation, coverage, implied_ftp)` for `records` (`(t, power)` pairs, already
    ordered by `t`) against `curve`, or `None` if there's no usable overlap (no records, no
    power data, or nothing falls inside the workout's planned duration). Split out of
    `correlate_activity` so a caller correlating one activity against many candidate workouts
    (see `rank_workouts_for_activity`) can fetch the activity's records once and reuse them,
    instead of re-fetching the same rows for every candidate.

    A literal `0` reading is treated the same as missing data (excluded, not paired as a real
    "no effort" sample): confirmed against a real activity's raw FIT records that a sensor/
    connection dropout reads as `power=0` for a few seconds at a time while cadence and heart
    rate carry on unaffected - real noise, not a genuine stop. No workout step ever targets 0
    (every target is a positive %FTP/watts value), so this can't mask an intentionally flat
    zero-effort block; it only drops noise that would otherwise count as a correlation outlier
    against whatever the plan expects at that moment.
    """
    if not records:
        return None
    start_t = records[0][0]
    pairs = []
    for t, power in records:
        if not power:
            continue
        expected = _sample_expected_at(curve, t - start_t)
        if expected is not None:
            pairs.append((expected, power))
    return _correlate_pairs(pairs, len(records))


def _cumulative_lap_ends(activity: Activity) -> list[int]:
    """`[lap 1's end, lap 1+2's end, lap 1+2+3's end, ...]`, in seconds from the start of the
    recording - the running total of `activity`'s own real device laps' `duration`, in `index`
    order. Every activity gets its raw, device-recorded laps persisted at upload time
    unconditionally (`uploads/processing.py::_ingest_activity`), before any workout-matching
    happens - and a match-scan candidate is by definition unmatched (`workout_id IS NULL`), so
    it's never had those replaced by `lap_derivation.replace_laps_with_derived` (which only ever
    runs against an activity's *matched* workout). So this is always the athlete's own real
    lap-button presses for a match-scan candidate, never anything derived from a plan - exactly
    the real-world signal `correlate_records_by_step_boundary` needs for a manual-ended step.
    """
    cumulative: list[int] = []
    total = 0
    for duration in activity.laps.order_by("index").values_list("duration", flat=True):
        total += duration
        cumulative.append(total)
    return cumulative


def correlate_records_by_step_boundary(
    workout: "Workout",
    activity: Activity,
    records: list[tuple[int, float | None, int | None]],
    reference: float,
    excluded_kinds: frozenset[str] = frozenset(),
) -> tuple[float, float, int | None] | None:
    """The distance-ended/manual-ended-step counterpart to `correlate_records`/
    `build_expected_curve` - see the module docstring for why those two step shapes can't be
    placed on a fixed, reusable time-based curve the way a time-ended one can. Walks `records`
    (`(t, distance_km, power)` tuples, already ordered by `t`) against `workout`'s own flattened
    steps directly, assigning each record to whichever step it falls in via a running `offset_t`/
    `offset_distance` carried forward across steps (the same technique `activities.
    lap_derivation` uses to slice a matched activity's laps), then correlates the resulting
    (expected_pct, actual_power) pairs.

    Three separate boundary strategies, one per `end_type`, chosen per step inside the loop:

    - `"time"`: the plan's own `duration` past wherever the walk currently is
      (`offset_t + step.duration`) - identical to `build_expected_curve`'s own segments, just
      evaluated fresh per candidate instead of precomputed once.
    - `"distance"`: the plan's own `distance` past wherever the walk currently is
      (`offset_distance + step.distance / 1000`) - see the module docstring for why this can't
      be predicted as a fixed time offset ahead of time (it depends on how fast *this*
      candidate actually covered it).
    - `"manual"` (only reachable when `target_type="open"` - `scannability_error` guarantees
      this pairing): there is no plan value to walk toward at all - a `WorkoutStep` with
      `end_type="manual"` never has a `duration` or `distance` (see the model's own
      `repeat_step_has_no_leaf_fields`-adjacent CHECK constraint), because a manual step's real
      length is *defined* as "however long until the athlete pressed lap." The only place that
      real length is recorded is the candidate's own raw device laps (`_cumulative_lap_ends`).
      So this branch instead finds the first of *this candidate's own* lap boundaries that falls
      at-or-after the walk's current position (`lap_ends`/`lap_idx`, both advanced monotonically
      so a later manual step never re-uses an earlier lap), and boundary-walks toward that,
      exactly like the `"time"` branch does toward a planned duration. This deliberately does
      *not* try to match "the Nth manual step" to "the Nth device lap" positionally - device
      laps don't reliably line up with a structured plan's own step count or order (see
      `lap_derivation`'s own docstring on why it doesn't trust them for boundaries either); the
      *next* lap boundary the walk encounters, whatever its index, is the most defensible
      available signal for "when did this specific open segment end". A manual step never
      contributes to the correlation itself (there's no target - `target_type="open"` means
      exactly that), it only needs to advance the walk correctly so the *next* real step's
      pairs are measured from the right starting point. If the candidate runs out of laps
      before this point (a device lap count smaller than expected, e.g. a merged/missing
      press), the remaining records are folded into this step and the walk ends there - the
      same "activity's own recording came up short" fallback every other end_type gets
      naturally when its own boundary is never reached.

    Unlike lap derivation, none of this needs pause-robust active-time accounting: a correlation
    over a full activity's worth of samples tolerates a handful of samples landing in the wrong
    step's bucket near a pause without materially moving the result, which a single lap's
    average power cannot.
    """
    if not records:
        return None
    n = len(records)
    record_idx = 0
    offset_t = records[0][0]
    offset_distance = records[0][1] or 0.0
    pairs: list[tuple[float, int]] = []
    lap_ends: list[int] | None = None  # lazily fetched - most workouts have no manual step at all
    lap_idx = 0

    for step, _ in flatten_persisted_steps(workout):
        if record_idx >= n:
            break
        if step.end_type == "time":
            boundary = offset_t + step.duration
            end_idx = record_idx
            while end_idx < n - 1 and records[end_idx][0] < boundary:
                end_idx += 1
        elif step.end_type == "distance":
            boundary = offset_distance + step.distance / 1000
            end_idx = record_idx
            while end_idx < n - 1 and (records[end_idx][1] is None or records[end_idx][1] < boundary):
                end_idx += 1
        else:  # "manual" - see this function's own docstring for the full reasoning.
            if lap_ends is None:
                lap_ends = _cumulative_lap_ends(activity)
            elapsed_so_far = offset_t - records[0][0]
            while lap_idx < len(lap_ends) and lap_ends[lap_idx] <= elapsed_so_far:
                lap_idx += 1
            end_idx = record_idx
            if lap_idx < len(lap_ends):
                boundary = records[0][0] + lap_ends[lap_idx]
                while end_idx < n - 1 and records[end_idx][0] < boundary:
                    end_idx += 1
                lap_idx += 1
            else:
                end_idx = n - 1

        # A manual/open step never contributes to the correlation - there's no target to
        # correlate against - regardless of excluded_kinds.
        if step.kind not in excluded_kinds and step.end_type != "manual":
            pct = _step_intensity_pct(step, reference)
            for _, _, power in records[record_idx : end_idx + 1]:
                if power:
                    pairs.append((pct, power))

        record_idx = end_idx + 1
        offset_t = records[end_idx][0]
        if records[end_idx][1] is not None:
            offset_distance = records[end_idx][1]

    return _correlate_pairs(pairs, n)


def correlate_activity(
    curve: list[tuple[int, int, float]], activity: Activity
) -> tuple[float, float, int | None] | None:
    """Returns `(correlation, coverage, implied_ftp)` for `activity` against `curve`, or `None`
    if there's no usable overlap. Thin wrapper around `correlate_records` for a caller that
    only has one workout to check and hasn't already fetched the activity's records."""
    records = list(activity.records.order_by("t").values_list("t", "power"))
    return correlate_records(curve, records)


def rank_workouts_for_activity(
    workouts: list["Workout"], activity: Activity, tolerance_seconds: int = DURATION_TOLERANCE_SECONDS
) -> list[tuple["Workout", float, float, int | None]]:
    """Ranks `workouts` by Pearson correlation of `activity`'s actual power stream against each
    one's planned %FTP-vs-time curve - the mirror image of `run_match_scan` (one activity vs.
    many candidate workouts, instead of one workout vs. many candidate activities). Backs the
    on-demand activity -> workout-library endpoint, and the ingest-time auto-match tie-break for
    when more than one same-day/sport `ScheduledWorkout` candidate exists.

    Every candidate is assumed to share the same athlete and sport as `activity` (both callers
    filter for this already), so the power-zone reference is looked up once and reused rather
    than recomputed per workout. Non-scannable candidates and ones outside `tolerance_seconds`
    (the validated default, widenable per-call for the on-demand endpoint - e.g. a distance-
    based activity's actual moving time can legitimately fall well outside a fixed-duration
    workout's planned duration) are skipped, cheapest check first: the persisted
    `workout.duration` column (no extra query) before `scannability_error`/`build_expected_curve`
    (which fetch `WorkoutStep` rows), so a large library doesn't pay a per-candidate steps fetch
    for every candidate. Returns `(workout, correlation, coverage, implied_ftp)` tuples, best
    match first.
    """
    records = list(activity.records.order_by("t").values_list("t", "power"))
    if not records:
        return []
    records_with_distance: list[tuple[int, float | None, int | None]] | None = None

    reference: float | None = None
    results = []
    for workout in workouts:
        if abs(workout.duration - activity.moving_time) > tolerance_seconds:
            continue
        if scannability_error(workout) is not None:
            continue
        if reference is None:
            reference = _power_reference(workout)
        flattened = flatten_persisted_steps(workout)
        if _needs_step_boundary_walk(flattened):
            if records_with_distance is None:
                records_with_distance = list(activity.records.order_by("t").values_list("t", "distance_km", "power"))
            result = correlate_records_by_step_boundary(workout, activity, records_with_distance, reference)
        else:
            curve = build_expected_curve(workout, reference=reference)
            result = correlate_records(curve, records)
        if result is None:
            continue
        r, coverage, implied_ftp = result
        results.append((workout, r, coverage, implied_ftp))

    results.sort(key=lambda row: -row[1])
    return results


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
    excluded_kinds = frozenset(scan.excluded_step_kinds)
    flattened = flatten_persisted_steps(workout)
    needs_boundary_walk = _needs_step_boundary_walk(flattened)
    reference = _power_reference(workout)
    curve = (
        None
        if needs_boundary_walk
        else build_expected_curve(workout, reference=reference, excluded_kinds=excluded_kinds)
    )
    # workout.duration, not curve[-1][1]: the curve's last entry can end before the workout's
    # real total duration whenever a trailing step (typically the cooldown) is excluded - the
    # activity recording still covers that phase in real time, so the duration pre-filter below
    # needs the true total, which the persisted column always has regardless of exclusions.
    total_planned_duration = workout.duration

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
        if needs_boundary_walk:
            records = list(activity.records.order_by("t").values_list("t", "distance_km", "power"))
            result = correlate_records_by_step_boundary(workout, activity, records, reference, excluded_kinds)
        else:
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
