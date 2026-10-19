"""Walks an activity's own per-second `Record` stream forward with a cursor, slicing it into one
segment per step - by elapsed time for a "time" step, by distance for a "distance" one - exactly
like a GPS device cutting a new lap at each of a structured workout's own step boundaries. Shared
by `lap_derivation.derive_laps_from_workout` (a matched workout's own steps) and the custom splits
mode (ad-hoc client-supplied steps) so the two can't drift apart. Mirrors backend_java's
RecordStepWalker.
"""

from typing import Any, TypedDict


class StepSpec(TypedDict):
    end_type: str  # "time" or "distance"
    duration: int | None  # seconds - required (and only meaningful) for end_type="time"
    distance: float | None  # meters - required (and only meaningful) for end_type="distance"


# A row is (t, distance_km, ...) - only these first two columns are read here; any further
# columns are opaque per-sample data a caller queried for its own aggregation (power, heartrate,
# altitude, ...), passed through untouched in each segment's "rows".
Row = tuple[Any, ...]


class Segment(TypedDict):
    rows: list[Row]
    time_s: int
    distance_km: float


def _observed_duration(rows: list[Row]) -> int:
    return rows[-1][0] - rows[0][0]


def _observed_distance_km(rows: list[Row]) -> float:
    dist_values = [r[1] for r in rows if r[1] is not None]
    return (dist_values[-1] - dist_values[0]) if len(dist_values) >= 2 else 0.0


def walk_steps(rows: list[Row], steps: list[StepSpec]) -> tuple[list[Segment], Segment | None]:
    """Returns (segments, trailing): `segments` has one entry per step that had any recording
    left to consume, in order - a strict prefix of `steps` (if the recording is shorter than the
    plan, later steps simply produce no segment, same as an athlete who stopped early - a real,
    unremarkable case). `trailing` is a final, unlinked segment for any recording left over after
    the last step (the recording ran longer than the plan), or `None` if there wasn't any.

    Each segment's `time_s`/`distance_km` use the step's own planned value once its boundary is
    genuinely reached, not the observed sample delta - two adjacent segments share their boundary
    sample (it closes one segment and opens the next), so diffing each one's own first/last row
    independently would undercount every interior segment by one unit. The non-governing
    dimension (and an un-reached boundary, on either dimension) always reflects what was actually
    recorded.
    """
    if not rows:
        return [], None

    n = len(rows)
    # A synthetic "active time" clock, running alongside the real per-row t: it advances exactly
    # like t (and is identical to it) except that any single inter-sample gap - a pause/resume
    # leaves one, since the device stops recording rather than freezing its clock - contributes
    # at most one second, matching how a device's own laps track total_timer_time (excluding
    # paused duration) rather than total_elapsed_time. Using this in place of raw t for
    # time-based boundaries stops a pause from being silently donated in full to whichever step's
    # boundary walk happens to cross it, which would otherwise push that step's real end (and
    # every later step's) later than the true transition and sweep in samples from the wrong
    # phase. It has to be a single running clock carried forward across steps, the same way
    # offset_t/boundary already worked - resetting it fresh at each step's own start would make
    # every step (not just the ones actually touching a gap) end one sample later than its
    # target, since adjacent segments share their boundary sample.
    active_t = [rows[0][0]] * n
    for i in range(1, n):
        active_t[i] = active_t[i - 1] + min(rows[i][0] - rows[i - 1][0], 1)

    record_idx = 0
    offset_t = active_t[0]
    offset_distance = rows[0][1] or 0.0
    segments: list[Segment] = []

    for step in steps:
        if record_idx >= n:
            break
        if step["end_type"] == "time":
            boundary = offset_t + step["duration"]
            end_idx = record_idx
            while end_idx < n - 1 and active_t[end_idx] < boundary:
                end_idx += 1
            reached = active_t[end_idx] >= boundary
        else:
            boundary = offset_distance + step["distance"] / 1000
            end_idx = record_idx
            while end_idx < n - 1 and (rows[end_idx][1] is None or rows[end_idx][1] < boundary):
                end_idx += 1
            reached = rows[end_idx][1] is not None and rows[end_idx][1] >= boundary

        segment_rows = rows[record_idx : end_idx + 1]
        if step["end_type"] == "time":
            time_s = step["duration"] if reached else _observed_duration(segment_rows)
            distance_km = _observed_distance_km(segment_rows)
        else:
            distance_km = (step["distance"] / 1000) if reached else _observed_distance_km(segment_rows)
            time_s = _observed_duration(segment_rows)
        segments.append({"rows": segment_rows, "time_s": time_s, "distance_km": distance_km})

        record_idx = end_idx + 1
        offset_t = active_t[end_idx]
        if rows[end_idx][1] is not None:
            offset_distance = rows[end_idx][1]

    trailing: Segment | None = None
    if record_idx < n:
        trailing_rows = rows[record_idx:]
        trailing = {
            "rows": trailing_rows,
            "time_s": _observed_duration(trailing_rows),
            "distance_km": _observed_distance_km(trailing_rows),
        }

    return segments, trailing
