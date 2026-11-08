"""Issue #501 (phase 1, part 2): applies a merge's selected fields from a source activity onto a
target, given an already-resolved time offset (see merge_alignment.align) and null policy.
``preview_merge_fields`` runs the exact same computation as ``apply_merge_fields`` without
writing anything, mirroring overlap_detection.py's preview_resolution/resolve_pair split - a
preview and the real apply can never disagree on what would happen. No API endpoint wires this up
yet; that's a separate PR, as is the `merge` audit record. Mirrors backend_java's
ActivityMergeApplyService - keep the two in sync when changing either.

Derived summaries (avg/NP/IF/TSS, max values, TRIMP, etc.) are never touched here - recomputing
them from the rewritten streams is the shared post-processing chain's job, not this module's.
"""

from dataclasses import dataclass
from typing import Literal

from django.db import transaction

from .models import Activity, Record

NullPolicy = Literal["overwrite", "keep_original"]
# Issue #503 (phase 1): how POST .../merge copies a field from source onto target. "overwrite"
# (the default, and the only mode before this issue) rewrites the target's own Record rows in
# place, per null_policy - apply_merge_fields above. "attach" doesn't touch the target's existing
# primary stream at all; it keeps the source's stream alongside as a non-primary ActivitySource/
# ActivityStream, which set_primary_source can promote later - see
# activity_source_service.attach_power_stream. Phase 1 only supports "attach" for the "power"
# field; any other field with copy_mode="attach" is rejected.
CopyMode = Literal["overwrite", "attach"]

# Per-second channels on Record - copying any of these rewrites the target's existing rows
# (the target's timeline is authoritative; no new rows are ever created or deleted).
STREAM_FIELDS = (
    "power",
    "heartrate",
    "cadence",
    "altitude",
    "lat",
    "lng",
    "speed",
    "distance_km",
    "air_temp",
    "humidity",
    "skin_temp",
    "core_temp",
    "heat_strain",
)
# Single-value, user-editable fields on Activity - a straight copy, no time offset or null
# policy involved (those only matter for a per-second stream).
ACTIVITY_FIELDS = (
    "name",
    "avg_left_balance_pct",
    "start_weight_kg",
    "end_weight_kg",
    "fluids_ml",
    "bike_id",
    "shoe_id",
)

# Any field name outside STREAM_FIELDS/ACTIVITY_FIELDS is silently ignored rather than raising -
# this module has no API endpoint yet to validate request input against, and the two tuples
# above are themselves the whitelist a future endpoint's serializer will validate against.


@dataclass(frozen=True)
class FieldRewriteCounts:
    copied: int
    kept_original: int
    nulled: int


def _compute_stream_field(
    target_values: dict[int, object],
    source_values: dict[int, object],
    offset_seconds: int,
    null_policy: NullPolicy,
) -> tuple[dict[int, object], FieldRewriteCounts]:
    """Pure: for every t the target has, looks up the source's value at (t - offset_seconds) -
    offset_seconds is how much later the source started than the target (see
    merge_alignment.align's own docstring for the sign convention). A target second with no
    matching source row is treated the same as an explicit source null. Returns the new value
    for every target t (even where unchanged), plus the counts preview/apply both report."""
    new_values: dict[int, object] = {}
    copied = kept_original = nulled = 0
    for t, target_value in target_values.items():
        source_value = source_values.get(t - offset_seconds)
        if source_value is not None:
            new_values[t] = source_value
            copied += 1
        elif null_policy == "keep_original":
            new_values[t] = target_value
            kept_original += 1
        else:
            new_values[t] = None
            nulled += 1
    return new_values, FieldRewriteCounts(copied, kept_original, nulled)


def _compute_activity_field(target: Activity, source: Activity, field: str) -> tuple[object, FieldRewriteCounts]:
    source_value = getattr(source, field)
    counts = FieldRewriteCounts(1, 0, 0) if source_value is not None else FieldRewriteCounts(0, 0, 1)
    return source_value, counts


def _compute(
    target: Activity, source: Activity, fields: list[str], null_policy: NullPolicy, offset_seconds: int
) -> tuple[dict[str, FieldRewriteCounts], dict[str, dict[int, object]], dict[str, object]]:
    """Shared by preview_merge_fields and apply_merge_fields. Returns (per-field counts, new
    stream values keyed by field then t, new activity-level field values) - apply_merge_fields
    writes the latter two; preview_merge_fields discards them."""
    counts: dict[str, FieldRewriteCounts] = {}
    new_stream_values: dict[str, dict[int, object]] = {}
    new_activity_values: dict[str, object] = {}

    stream_fields = [f for f in fields if f in STREAM_FIELDS]
    if stream_fields:
        target_rows = list(target.records.only("t", *stream_fields))
        source_rows = list(source.records.only("t", *stream_fields))
        for field in stream_fields:
            target_values = {row.t: getattr(row, field) for row in target_rows}
            source_values = {row.t: getattr(row, field) for row in source_rows}
            new_values, field_counts = _compute_stream_field(target_values, source_values, offset_seconds, null_policy)
            counts[field] = field_counts
            new_stream_values[field] = new_values

    for field in fields:
        if field in ACTIVITY_FIELDS:
            new_value, field_counts = _compute_activity_field(target, source, field)
            counts[field] = field_counts
            new_activity_values[field] = new_value

    return counts, new_stream_values, new_activity_values


def preview_merge_fields(
    target_id: str, source_id: str, fields: list[str], null_policy: NullPolicy, offset_seconds: int
) -> dict[str, FieldRewriteCounts]:
    """Same computation apply_merge_fields runs, without writing anything."""
    target = Activity.objects.get(pk=target_id)
    source = Activity.objects.get(pk=source_id)
    counts, _, _ = _compute(target, source, fields, null_policy, offset_seconds)
    return counts


def apply_merge_fields(
    target_id: str, source_id: str, fields: list[str], null_policy: NullPolicy, offset_seconds: int
) -> dict[str, FieldRewriteCounts]:
    """Writes the selected fields from source onto target - the target keeps its own id, so its
    comments, tags, gear, workout link and current_from history stay intact; only the listed
    fields' values change."""
    target = Activity.objects.get(pk=target_id)
    source = Activity.objects.get(pk=source_id)
    counts, new_stream_values, new_activity_values = _compute(target, source, fields, null_policy, offset_seconds)

    with transaction.atomic():
        if new_stream_values:
            changed_fields = list(new_stream_values.keys())
            target_rows = list(target.records.only("t", *changed_fields))
            changed_rows = []
            for row in target_rows:
                changed = False
                for field in changed_fields:
                    new_value = new_stream_values[field][row.t]
                    if getattr(row, field) != new_value:
                        setattr(row, field, new_value)
                        changed = True
                if changed:
                    changed_rows.append(row)
            if changed_rows:
                Record.objects.bulk_update(changed_rows, changed_fields)

        if new_activity_values:
            for field, value in new_activity_values.items():
                setattr(target, field, value)
            target.save(update_fields=list(new_activity_values.keys()))

    return counts
