from typing import Any


def _leaf_duration(step: dict[str, Any]) -> int:
    """Only `time` steps with a `duration` contribute — `distance`/`manual` steps
    are excluded because we have no pace assumption to convert distance to time
    without real activity history. This is an intentional simplification, not a bug.
    """
    if step.get("end_type") == "time":
        return step.get("duration") or 0
    return 0


def _total_duration(steps: list[dict[str, Any]]) -> int:
    total = 0
    for step in steps:
        if step["kind"] == "repeat":
            total += _total_duration(step.get("children") or []) * (step.get("repeat") or 1)
        else:
            total += _leaf_duration(step)
    return total


def _leaf_tss(step: dict[str, Any]) -> float:
    """TSS ~= hours * intensity_factor^2 * 100 for power targets, using the
    target_low/target_high midpoint (equal for a flat target, averaged for a
    ramp). Non-power targets have no direct IF equivalent, so pace/HR/cadence
    use a flatter approximation and `open` (no target) assumes a light effort —
    both intentional simplifications, matching the design prototype's calc.
    """
    low = step.get("target_low")
    low = low if low is not None else 60
    high = step.get("target_high")
    high = high if high is not None else low
    avg = (low + high) / 2
    hours = (step.get("duration") or 0) / 3600
    target_type = step.get("target_type")
    if target_type == "power":
        return hours * (avg / 100) ** 2 * 100
    if target_type == "open":
        return hours * 55
    return hours * (avg / 100) * 80


def _tss_sum(steps: list[dict[str, Any]]) -> float:
    total = 0.0
    for step in steps:
        if step["kind"] == "repeat":
            total += _tss_sum(step.get("children") or []) * (step.get("repeat") or 1)
        else:
            total += _leaf_tss(step)
    return total


def compute_duration_and_tss(steps: list[dict[str, Any]]) -> tuple[int, int]:
    """Recompute a workout's total duration (seconds) and TSS from its step tree.

    `steps` is a list of dicts, each either a leaf (`kind` in warmup/block/rec/cool,
    with `end_type`/`duration`/`target_type`/`target_low`/`target_high`) or a
    `repeat` group (`repeat` count + nested `children`, recursed and multiplied by
    `repeat`, to arbitrary nesting depth).
    """
    return _total_duration(steps), round(_tss_sum(steps))


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


def compute_chart_preview(steps: list[dict[str, Any]]) -> list[dict[str, float | int]]:
    """A small array of per-leaf average target intensity and duration
    (flattened/unrolled), denormalized onto `Workout.chart_preview` so the
    library list endpoint can render a mini interval chart per card — each bar
    sized proportionally to that leaf's actual duration, not shipping the full
    step tree.
    """
    preview = []
    for leaf in _flatten_leaves(steps):
        duration = leaf.get("duration") or 0
        if leaf.get("target_type") == "open":
            preview.append({"intensity": 40.0, "duration_seconds": duration})
            continue
        low = leaf.get("target_low")
        low = low if low is not None else 60
        high = leaf.get("target_high")
        high = high if high is not None else low
        preview.append({"intensity": (low + high) / 2, "duration_seconds": duration})
    return preview
