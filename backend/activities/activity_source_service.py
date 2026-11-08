"""Issue #503 (phase 1): keeps a second power stream alongside an activity instead of #501's
merge overwriting one copy with the other - ``attach_power_stream`` (the ``attach`` merge
``copy_mode``, called from merge_service.run_merge) and ``set_primary_source`` (swap which stream
is primary, callable any time afterward, not just right after an attach). Phase 1 only ever
creates/swaps "power" sources - see the field validation in both functions. Mirrors
backend_java's ActivitySourceService.java - keep the two in sync when changing either.
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


def set_primary_source(activity_id: str, field: str, source_id: str) -> Activity:
    """Swaps field on activity_id with the named ActivityStream's values - the activity's
    *current* primary values (today, always living in Record) are themselves saved as a new
    ActivitySource/ActivityStream pair first (origin "file" - they came from this activity's own
    upload, not an attach), so swapping never loses data either direction. Reruns the shared
    post-merge recompute cascade afterward - same cascade a real #501 merge or #498 primary swap
    already triggers."""
    if field != "power":
        raise ValidationError({"field": "field must be 'power' - phase 1 only supports power sources."})
    activity = get_object_or_404(Activity, pk=activity_id)
    new_primary_stream = get_object_or_404(ActivityStream, activity_id=activity_id, field=field, source_id=source_id)

    rows = list(activity.records.order_by("t"))

    with transaction.atomic():
        # Snapshot the activity's current primary (Record.power) as its own attached source -
        # unconditionally, so swapping back later is always possible.
        original_source = ActivitySource.objects.create(
            activity=activity, label=activity.name, device=activity.device, sensor_kind=field, origin="file"
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

        activity.bike_power_source = classify(new_primary_stream.source.device or activity.device)
        activity.save(update_fields=["bike_power_source"])

        recompute_after_merge(activity_id)

    activity.refresh_from_db()
    return activity


def list_sources(activity_id: str) -> list[SourceSummary]:
    """isPrimary reflects whether this source's own device classification matches the activity's
    *current* primary reading for its field - phase 1 only ever has "power" sources, so this is
    always a _current_primary_source comparison; it's a derived read, not a stored flag, since
    the primary stream itself always lives in Record, never in an ActivitySource row (see this
    module's own docstring)."""
    activity = get_object_or_404(Activity, pk=activity_id)
    primary = _current_primary_source(activity)
    return [
        SourceSummary(
            id=source.id,
            label=source.label,
            device=source.device,
            sensor_kind=source.sensor_kind,
            origin=source.origin,
            created_at=source.created_at,
            is_primary=source.sensor_kind == "power" and classify(source.device) == primary,
        )
        for source in activity.sources.order_by("created_at")
    ]
