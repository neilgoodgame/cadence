"""Issue #503 (phases 1-2): keeps a second power stream alongside an activity instead of #501's
merge overwriting one copy with the other - ``attach_power_stream`` (the ``attach`` merge
``copy_mode``, called from merge_service.run_merge, bike only),
``create_parse_time_running_power_source`` (a run's non-preferred native/Stryd candidate, kept
at parse time, phase 2), and ``set_primary_source`` (swap which stream is primary, callable any
time afterward for either sport). Phase 1/2 only ever create/swap "power" sources - see the
field validation in ``set_primary_source``. Mirrors backend_java's ActivitySourceService.java -
keep the two in sync when changing either.
"""

from dataclasses import dataclass
from typing import NamedTuple

from django.db import transaction
from django.shortcuts import get_object_or_404
from rest_framework.exceptions import ValidationError

from .merge_alignment import align
from .merge_postprocess import recompute_after_merge
from .models import Activity, ActivitySource, ActivityStream, Record


@dataclass(frozen=True)
class AttachResult:
    source_id: str | None
    offset_seconds: int
    alignment_score: float | None
    became_primary: bool
    copied: int
    nulled: int


class SourceSummary(NamedTuple):
    id: str
    label: str
    device: str
    sensor_kind: str
    origin: str
    created_at: object
    is_primary: bool


def classify(device: str | None) -> str:
    """Zwift (trainer power, read after drivetrain losses) vs everything else (treated as
    pedals, by elimination) - the same heuristic overlap_detection.py's built-in bike merge rule
    and its own ``_is_zwift`` already use. Phase 1 deliberately reuses this rather than adding
    new FIT device_info parsing - see issue #503's own Phase 1 scope ("covers the Zwift +
    Garmin case"); real ANT+ device-type classification for non-Zwift devices is a later
    refinement."""
    return "trainer" if device and device.lower().startswith("zwift") else "pedals"


def _current_primary_source(activity: Activity) -> str:
    """An activity's own currently-primary power classification - its own bike_power_source once
    that's been set by a previous attach/swap, or else classified from its device (the file's own
    reading, never swapped)."""
    return activity.bike_power_source or classify(activity.device)


def _resolve_offset(target: Activity, source: Activity, offset_seconds: int | None) -> tuple[int, float | None]:
    """Same resolution merge_service._resolve_offset uses for ``overwrite`` - an explicit
    offset_seconds skips alignment entirely, reported with a None score since nothing was
    actually correlated."""
    if offset_seconds is not None:
        return offset_seconds, None
    initial_offset = round((source.start_date - target.start_date).total_seconds())
    target_records = list(target.records.values_list("t", "cadence", "power"))
    source_records = list(source.records.values_list("t", "cadence", "power"))
    result = align(target_records, source_records, initial_offset)
    return result.offset_seconds, result.score


def attach_power_stream(
    target_id: str, source_id: str, offset_seconds: int | None = None, preview: bool = False
) -> AttachResult:
    """Attaches source's power stream onto target as a non-primary ActivityStream, aligned to
    target's own timeline using the same cross-correlation ``run_merge`` uses for ``overwrite``.
    If the athlete's bike_power_source preference says the attached stream should be primary
    instead of target's current one, immediately swaps via set_primary_source before returning -
    the athlete can always swap back later. preview=True computes and returns the result without
    writing anything (no transaction-rollback trick needed here, unlike ``overwrite`` - nothing
    is written unless preview is False, so there's nothing to undo)."""
    target = get_object_or_404(Activity, pk=target_id)
    source = get_object_or_404(Activity, pk=source_id)
    if target.pk == source.pk:
        raise ValidationError({"source_id": "An activity cannot be merged with itself."})
    if target.athlete_id != source.athlete_id:
        raise ValidationError({"source_id": "Both activities must belong to the same athlete."})

    target_rows = list(target.records.order_by("t"))
    source_rows = list(source.records.order_by("t"))

    resolved_offset, score = _resolve_offset(target, source, offset_seconds)

    # Build the attached array on the target's own timeline (t0..t0+n-1, matching Record's t) -
    # source's power at target-local t is whatever the source recorded at its own local time
    # (t - offset_seconds), same lookup direction merge_apply's overwrite path uses.
    source_power_by_t = {row.t: row.power for row in source_rows if row.power is not None}
    t0 = min((row.t for row in target_rows), default=0)
    t_max = max((row.t for row in target_rows), default=t0)
    values: list[int | None] = [None] * (t_max - t0 + 1)
    copied = nulled = 0
    for t in range(t0, t_max + 1):
        source_value = source_power_by_t.get(t - resolved_offset)
        if source_value is not None:
            values[t - t0] = source_value
            copied += 1
        else:
            nulled += 1

    if preview:
        return AttachResult(None, resolved_offset, score, False, copied, nulled)

    with transaction.atomic():
        activity_source = ActivitySource.objects.create(
            activity=target, label=source.name, device=source.device, sensor_kind="power", origin="attached"
        )
        ActivityStream.objects.create(
            activity=target, field="power", source=activity_source, t0=t0, values=values, primary_of=False
        )

        athlete = target.athlete
        preference = athlete.bike_power_source
        became_primary = False
        if (
            preference != "any"
            and classify(source.device) == preference
            and _current_primary_source(target) != preference
        ):
            set_primary_source(target_id, "power", activity_source.id)
            became_primary = True

    return AttachResult(activity_source.id, resolved_offset, score, became_primary, copied, nulled)


def create_parse_time_running_power_source(
    activity: Activity, t_series: list[int], other_values_raw: list[int | None], other_kind: str
) -> None:
    """Issue #503 (phase 2): stores a run's non-preferred native/Stryd candidate (the one
    uploads.processing._select_running_power_source didn't pick) as a new, non-primary
    FILE-origin source/stream, so a later running_power_source preference change can re-designate
    it instead of only filtering the activity out via Activity.matches_running_power_preference.
    No-op if the other candidate has no data at all in this file (nothing to keep) - matches
    Activity.power_source's own "blank when there's no such choice to record" convention. Called
    once per RUN/FIT activity right after it's saved. Unlike attach_power_stream, there is no
    alignment step - both candidates already share the activity's own single parsed timeline
    (same t series, same order), so t_series/other_values_raw are zipped by index directly."""
    if not any(v is not None for v in other_values_raw):
        return
    t0 = t_series[0] if t_series else 0
    t_max = t_series[-1] if t_series else t0
    values: list[int | None] = [None] * (t_max - t0 + 1)
    for t, value in zip(t_series, other_values_raw, strict=True):
        if value is not None:
            values[t - t0] = value

    with transaction.atomic():
        source = ActivitySource.objects.create(
            activity=activity,
            label=activity.name,
            device=activity.device,
            sensor_kind="power",
            origin="file",
            running_power_kind=other_kind,
        )
        ActivityStream.objects.create(
            activity=activity, field="power", source=source, t0=t0, values=values, primary_of=False
        )


def set_primary_source(activity_id: str, field: str, source_id: str) -> Activity:
    """Swaps field on activity_id with the named ActivityStream's values, then reruns the shared
    post-merge recompute cascade - same cascade a real #501 merge or #498 primary swap already
    triggers. See _swap_primary_stream for the data-only half this wraps."""
    with transaction.atomic():
        activity = _swap_primary_stream(activity_id, field, source_id)
        recompute_after_merge(activity_id)
    activity.refresh_from_db()
    return activity


def _swap_primary_stream(activity_id: str, field: str, source_id: str) -> Activity:
    """The data-only half of set_primary_source - everything except the recompute cascade. Besides
    set_primary_source itself, only athletes.tasks.recompute_running_power_source's bulk loop
    calls this directly, deferring the athlete-wide cascade (merge_postprocess
    .recompute_after_duplicate_change's own full-ledger rebuild) to run once per athlete instead
    of once per swapped activity, which would otherwise be quadratic in the athlete's activity
    count - see recompute_activity_level_only's own docstring.

    The activity's *current* primary values (today, always living in Record) are themselves
    saved as a new ActivitySource/ActivityStream pair first (origin "file" - they came from this
    activity's own upload, not an attach), so swapping never loses data either direction."""
    if field != "power":
        raise ValidationError({"field": "field must be 'power' - phase 1 only supports power sources."})
    activity = get_object_or_404(Activity, pk=activity_id)
    new_primary_stream = get_object_or_404(ActivityStream, activity_id=activity_id, field=field, source_id=source_id)

    rows = list(activity.records.order_by("t"))

    with transaction.atomic():
        # Snapshot the activity's current primary (Record.power) as its own attached source -
        # unconditionally, so swapping back later is always possible.
        original_source = ActivitySource.objects.create(
            activity=activity,
            label=activity.name,
            device=activity.device,
            sensor_kind=field,
            origin="file",
            running_power_kind=(activity.power_source if activity.sport == "run" else ""),
        )
        t0 = min((row.t for row in rows), default=0)
        t_max = max((row.t for row in rows), default=t0)
        original_values: list[int | None] = [None] * (t_max - t0 + 1)
        for row in rows:
            original_values[row.t - t0] = row.power
        ActivityStream.objects.create(
            activity=activity, field=field, source=original_source, t0=t0, values=original_values, primary_of=False
        )

        # Now write new_primary_stream's values into Record.power.
        new_values = new_primary_stream.values
        new_t0 = new_primary_stream.t0
        changed_rows = []
        for row in rows:
            index = row.t - new_t0
            row.power = new_values[index] if 0 <= index < len(new_values) else None
            changed_rows.append(row)
        Record.objects.bulk_update(changed_rows, ["power"])

        new_source = new_primary_stream.source
        if activity.sport == "bike":
            activity.bike_power_source = classify(new_source.device or activity.device)
            activity.save(update_fields=["bike_power_source"])
        elif activity.sport == "run" and new_source.running_power_kind:
            activity.power_source = new_source.running_power_kind
            activity.save(update_fields=["power_source"])

    return activity


def _is_primary_power(source: ActivitySource, activity: Activity) -> bool:
    """is_primary reflects whether this source matches the activity's *current* primary reading
    for its field - bike by device classification (_current_primary_source), running by
    comparing the source's own running_power_kind against Activity.power_source. It's a derived
    read, not a stored flag, since the primary stream itself always lives in Record, never in an
    ActivitySource row (see this module's own docstring)."""
    if source.sensor_kind != "power":
        return False
    if activity.sport == "run":
        return bool(source.running_power_kind) and source.running_power_kind == activity.power_source
    return classify(source.device) == _current_primary_source(activity)


def list_sources(activity_id: str) -> list[SourceSummary]:
    activity = get_object_or_404(Activity, pk=activity_id)
    return [
        SourceSummary(
            id=source.id,
            label=source.label,
            device=source.device,
            sensor_kind=source.sensor_kind,
            origin=source.origin,
            created_at=source.created_at,
            is_primary=_is_primary_power(source, activity),
        )
        for source in activity.sources.order_by("created_at")
    ]
