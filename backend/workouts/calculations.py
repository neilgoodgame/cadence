import copy
from typing import Any

# Fallback power reference (watts) used to normalize a "watts"-unit step when the athlete
# hasn't set a real ftp/critical_run_power - matches the display-only placeholder already used
# on the frontend (frontend/src/screens/workouts/workoutTree.ts, workoutExport.ts).
_DEFAULT_POWER_REFERENCE = 265


def normalize_power_units(steps: list[dict[str, Any]], power_reference: float | None) -> list[dict[str, Any]]:
    """Returns a copy of `steps` where every "watts"-unit power leaf's target_low/target_high
    are replaced by their %FTP-equivalent, so duration/TSS/chart-preview math (which only ever
    understands %-space) can stay completely unit-blind. The real, persisted WorkoutStep rows
    keep whatever the athlete actually entered - this copy is ephemeral, used only to feed
    compute_duration_and_tss/compute_chart_preview.
    """
    reference = power_reference if power_reference is not None else _DEFAULT_POWER_REFERENCE
    out: list[dict[str, Any]] = []
    for step in steps:
        step = copy.copy(step)
        if step.get("kind") == "repeat":
            step["children"] = normalize_power_units(step.get("children") or [], power_reference)
        elif step.get("target_type") == "power" and step.get("power_unit") == "watts":
            low = step.get("target_low")
            high = step.get("target_high")
            step["target_low"] = (low / reference) * 100 if low is not None else None
            step["target_high"] = (high / reference) * 100 if high is not None else None
        out.append(step)
    return out


def _target_avg(step: dict[str, Any]) -> float:
    low = step.get("target_low")
    low = low if low is not None else 60
    high = step.get("target_high")
    high = high if high is not None else low
    return (low + high) / 2


def _leaf_duration(step: dict[str, Any], sport: str, pace_reference: float | None) -> int:
    """`time` steps use their stored `duration` directly. A `distance`-ended running step
    targeting `power` or `pace` infers one from the athlete's threshold pace (distance / a pace
    implied by the target's %) - power and pace percentages share the same %-of-threshold-effort
    scale for running (see athletes/zones.py's DEFAULT_PACE_ZONES: both are calibrated to a
    ~60-minute threshold effort), so this only applies to `sport == "run"`; a cyclist's speed for
    a given %FTP is too dependent on terrain/aero for the same assumption to hold. Every other
    distance/manual combination (HR/cadence/open targets, a bike workout, or no threshold pace
    set yet) still has no such assumption available and stays at 0 - intentional, not a bug.
    """
    if step.get("end_type") == "time":
        return step.get("duration") or 0
    if (
        sport == "run"
        and step.get("end_type") == "distance"
        and step.get("target_type") in ("power", "pace")
        and step.get("distance")
        and pace_reference
    ):
        avg_pct = _target_avg(step)
        if avg_pct <= 0:
            return 0
        pace_seconds_per_km = pace_reference * 100 / avg_pct
        return round((step["distance"] / 1000) * pace_seconds_per_km)
    return 0


def _total_duration(steps: list[dict[str, Any]], sport: str, pace_reference: float | None) -> int:
    total = 0
    for step in steps:
        if step["kind"] == "repeat":
            total += _total_duration(step.get("children") or [], sport, pace_reference) * (step.get("repeat") or 1)
        else:
            total += _leaf_duration(step, sport, pace_reference)
    return total


def _leaf_tss(step: dict[str, Any], sport: str, pace_reference: float | None) -> float:
    """TSS ~= hours * intensity_factor^2 * 100, using the target_low/target_high
    midpoint as the IF (equal for a flat target, averaged for a ramp). `pace`
    uses the same squared formula as `power` - TSS is defined so 1 hour at
    threshold (IF=1.0) is 100, and effort above threshold compounds faster than
    linearly, exactly like power's IF^2 - so a flat multiplier would both miss
    the 100-at-threshold calibration point and understate hard efforts more the
    harder they get. HR/cadence keep a flatter approximation since HR lags real
    effort (undershooting short hard efforts, so squaring it would overcorrect
    the wrong way) and cadence carries no intensity signal at all; `open` (no
    target) assumes a light effort. Reuses `_leaf_duration` (not the step's own
    raw, often-null, `duration` field) so an inferred distance+power/pace
    duration feeds TSS too.
    """
    avg = _target_avg(step)
    hours = _leaf_duration(step, sport, pace_reference) / 3600
    target_type = step.get("target_type")
    if target_type in ("power", "pace"):
        return hours * (avg / 100) ** 2 * 100
    if target_type == "open":
        return hours * 55
    return hours * (avg / 100) * 80


def _tss_sum(steps: list[dict[str, Any]], sport: str, pace_reference: float | None) -> float:
    total = 0.0
    for step in steps:
        if step["kind"] == "repeat":
            total += _tss_sum(step.get("children") or [], sport, pace_reference) * (step.get("repeat") or 1)
        else:
            total += _leaf_tss(step, sport, pace_reference)
    return total


def compute_duration_and_tss(
    steps: list[dict[str, Any]], sport: str = "", pace_reference: float | None = None
) -> tuple[int, int]:
    """Recompute a workout's total duration (seconds) and TSS from its step tree.

    `steps` is a list of dicts, each either a leaf (`kind` in warmup/block/rec/cool,
    with `end_type`/`duration`/`target_type`/`target_low`/`target_high`) or a
    `repeat` group (`repeat` count + nested `children`, recursed and multiplied by
    `repeat`, to arbitrary nesting depth). `sport`/`pace_reference` enable the
    distance-step duration inference in `_leaf_duration` - omit them to get the old
    time-steps-only behavior.
    """
    return _total_duration(steps, sport, pace_reference), round(_tss_sum(steps, sport, pace_reference))


def _flatten_leaves(steps: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Unrolls `repeat` groups into their repeated leaf sequence (same leaves
    reused per repetition), matching the frontend's `flattenLeaves`.
    """
    out: list[dict[str, Any]] = []
    for step in steps:
        if step["kind"] == "repeat":
            for _ in range(step.get("repeat") or 1):
                out.extend(_flatten_leaves(step.get("children") or []))
        else:
            out.append(step)
    return out


def compute_chart_preview(
    steps: list[dict[str, Any]], sport: str = "", pace_reference: float | None = None
) -> list[dict[str, float | int]]:
    """A small array of per-leaf average target intensity and duration
    (flattened/unrolled), denormalized onto `Workout.chart_preview` so the
    library list endpoint can render a mini interval chart per card — each bar
    sized proportionally to that leaf's actual duration, not shipping the full
    step tree. Uses `_leaf_duration` (not the leaf's raw `duration` field) so a
    distance-ended running step's inferred duration sizes its bar correctly too.
    """
    preview = []
    for leaf in _flatten_leaves(steps):
        duration = _leaf_duration(leaf, sport, pace_reference)
        if leaf.get("target_type") == "open":
            preview.append({"intensity": 40.0, "duration_seconds": duration})
            continue
        low = leaf.get("target_low")
        low = low if low is not None else 60
        high = leaf.get("target_high")
        high = high if high is not None else low
        preview.append({"intensity": (low + high) / 2, "duration_seconds": duration})
    return preview
