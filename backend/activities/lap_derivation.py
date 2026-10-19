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
from .record_walk import Segment, StepSpec, walk_steps

if TYPE_CHECKING:
    from workouts.models import Workout


def _segment_avgs(records: list[tuple[int, float | None, int | None, int | None]]) -> dict[str, Any]:
    powers = [r[2] for r in records if r[2] is not None]
    hrs = [r[3] for r in records if r[3] is not None]
    return {
        "avg_power": round(sum(powers) / len(powers)) if powers else None,
        "avg_hr": round(sum(hrs) / len(hrs)) if hrs else None,
    }


def _lap_from_segment(activity: Activity, index: int, step: Any, repeat_index: int | None, segment: Segment) -> Lap:
    return Lap(
        activity=activity,
        index=index,
        workout_step=step,
        repeat_index=repeat_index,
        duration=segment["time_s"],
        distance_km=segment["distance_km"],
        **_segment_avgs(segment["rows"]),
    )


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

    step_specs: list[StepSpec] = [
        {"end_type": step.end_type, "duration": step.duration, "distance": step.distance} for step, _ in flattened
    ]
    segments, trailing = walk_steps(records, step_specs)

    laps: list[Lap] = [
        _lap_from_segment(activity, index, step, repeat_index, segment)
        for index, ((step, repeat_index), segment) in enumerate(zip(flattened, segments, strict=False), start=1)
    ]
    if trailing is not None:
        laps.append(_lap_from_segment(activity, len(laps) + 1, None, None, trailing))

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
