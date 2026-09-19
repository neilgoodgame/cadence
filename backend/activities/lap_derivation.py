"""Derives `Lap` rows for an activity matched to a workout, by slicing the activity's own
per-second `Record` stream at the workout's own step boundaries (by elapsed time for
`end_type="time"` steps, by distance for `end_type="distance"` steps) - rather than trying to
reconcile the device's own FIT-file lap markers against the workout, which don't reliably line
up 1:1 with the workout's steps (a trailing "stop recording" lap, a skipped rep, an extra lap
press). See `workouts.calculations.flatten_persisted_steps` for how repeat groups unroll while
still pointing every repetition at the same underlying `WorkoutStep` row.
"""

from typing import TYPE_CHECKING, Any

from workouts.calculations import flatten_persisted_steps

from .models import Activity, Lap

if TYPE_CHECKING:
    from workouts.models import Workout


def _segment_avgs(records: list[tuple[int, float | None, int | None, int | None]]) -> dict[str, Any]:
    powers = [r[2] for r in records if r[2] is not None]
    hrs = [r[3] for r in records if r[3] is not None]
    return {
        "avg_power": round(sum(powers) / len(powers)) if powers else None,
        "avg_hr": round(sum(hrs) / len(hrs)) if hrs else None,
    }


def _observed_duration(records: list[tuple[int, float | None, int | None, int | None]]) -> int:
    return records[-1][0] - records[0][0]


def _observed_distance_km(records: list[tuple[int, float | None, int | None, int | None]]) -> float:
    dist_values = [r[1] for r in records if r[1] is not None]
    return (dist_values[-1] - dist_values[0]) if len(dist_values) >= 2 else 0.0


def derive_laps_from_workout(activity: Activity, workout: "Workout") -> list[Lap] | None:
    """Returns the derived (unsaved) `Lap` rows for `activity`, sliced at `workout`'s own step
    boundaries, or `None` if derivation isn't possible for this workout/activity pair - a
    `manual`-ended step anywhere in the plan (no derivable boundary: a manual step's real
    duration is however long the athlete held it before pressing lap, information only the
    device's own laps have), a `time`/`distance` step missing its target value, or no recorded
    data at all. Callers should leave the activity's existing laps untouched in that case
    rather than persist a garbled partial result.

    If the activity's own recording is shorter than the plan, later steps simply produce no
    lap (the loop runs out of records) - a real, unremarkable case (the athlete stopped early).
    If it's longer, the trailing remainder becomes one final unlinked lap (`workout_step=None`)
    rather than being dropped.
    """
    flattened = flatten_persisted_steps(workout)
    for step, _ in flattened:
        if step.end_type == "manual":
            return None
        if step.end_type == "time" and not step.duration:
            return None
        if step.end_type == "distance" and not step.distance:
            return None

    records = list(activity.records.order_by("t").values_list("t", "distance_km", "power", "heartrate"))
    if not records:
        return None

    n = len(records)
    # A synthetic "active time" clock, running alongside the real per-record t: it advances
    # exactly like t (and is identical to it) except that any single inter-sample gap - a
    # pause/resume leaves one, since the device stops recording rather than freezing its clock
    # - contributes at most one second, matching how a device's own laps track
    # total_timer_time (excluding paused duration) rather than total_elapsed_time. Using this
    # in place of raw t for time-based boundaries stops a pause from being silently donated in
    # full to whichever step's boundary walk happens to cross it, which would otherwise push
    # that step's real end (and every later step's) later than the true transition and sweep in
    # samples from the wrong phase. It has to be a single running clock carried forward across
    # steps, the same way offset_t/boundary already worked - resetting it fresh at each step's
    # own start would make every step (not just the ones actually touching a gap) end one
    # sample later than its target, since adjacent segments share their boundary sample.
    active_t = [records[0][0]] * n
    for i in range(1, n):
        active_t[i] = active_t[i - 1] + min(records[i][0] - records[i - 1][0], 1)

    record_idx = 0
    offset_t = active_t[0]
    offset_distance = records[0][1] or 0.0
    laps: list[Lap] = []
    index = 1

    for step, repeat_index in flattened:
        if record_idx >= n:
            break
        if step.end_type == "time":
            boundary = offset_t + step.duration
            end_idx = record_idx
            while end_idx < n - 1 and active_t[end_idx] < boundary:
                end_idx += 1
            reached = active_t[end_idx] >= boundary
        else:  # "distance"
            boundary = offset_distance + step.distance / 1000
            end_idx = record_idx
            while end_idx < n - 1 and (records[end_idx][1] is None or records[end_idx][1] < boundary):
                end_idx += 1
            reached = records[end_idx][1] is not None and records[end_idx][1] >= boundary

        segment = records[record_idx : end_idx + 1]
        # The governing dimension (the one `end_type` targets) uses the *planned* value once the
        # boundary is genuinely reached, not the observed sample delta - two adjacent segments
        # share their boundary sample (it closes one segment and opens the next), so diffing
        # each segment's own first/last sample independently would undercount every interior
        # segment by one unit. The non-governing dimension has no such plan to fall back on and
        # always reflects what was actually recorded, same as an un-reached (activity ended
        # early) boundary on either dimension.
        if step.end_type == "time":
            duration = step.duration if reached else _observed_duration(segment)
            distance_km = _observed_distance_km(segment)
        else:
            distance_km = (step.distance / 1000) if reached else _observed_distance_km(segment)
            duration = _observed_duration(segment)
        laps.append(
            Lap(
                activity=activity,
                index=index,
                workout_step=step,
                repeat_index=repeat_index,
                duration=duration,
                distance_km=distance_km,
                **_segment_avgs(segment),
            )
        )
        index += 1
        record_idx = end_idx + 1
        offset_t = active_t[end_idx]
        if records[end_idx][1] is not None:
            offset_distance = records[end_idx][1]

    if record_idx < n:
        trailing = records[record_idx:]
        laps.append(
            Lap(
                activity=activity,
                index=index,
                workout_step=None,
                repeat_index=None,
                duration=_observed_duration(trailing),
                distance_km=_observed_distance_km(trailing),
                **_segment_avgs(trailing),
            )
        )

    return laps


def replace_laps_with_derived(activity: Activity, workout: "Workout") -> bool:
    """Derives and persists laps for `activity` from `workout`'s own steps, replacing whatever
    laps it currently has. Returns `False` (existing laps left untouched) if derivation wasn't
    possible - see `derive_laps_from_workout`.
    """
    laps = derive_laps_from_workout(activity, workout)
    if laps is None:
        return False
    activity.laps.all().delete()
    Lap.objects.bulk_create(laps)
    return True
