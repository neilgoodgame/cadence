"""Issue #501 (phase 1, part 4): the merge orchestrator - resolves (or accepts an override of)
the time offset, rewrites the target's fields from the source, runs the shared post-processing
chain, optionally hides the source as a #498 duplicate, and records a Merge audit row.

A preview runs the exact same code inside one transaction and rolls it back - the same technique
athletes/threshold_history.py's manual-correction preview already uses (see its
_apply_manual_value's own ``transaction.set_rollback(True)``) - so preview and a real merge can
never disagree about outcome, and preview has zero side effects even though it isn't a separate,
hand-simulated computation. Mirrors backend_java's ActivityMergeService - keep the two in sync
when changing either.
"""

from typing import TypedDict

from django.db import transaction
from django.shortcuts import get_object_or_404
from rest_framework.exceptions import ValidationError

from gear.mileage import apply_duplicate_change

from .activity_source_service import VALID_FIELDS as ATTACHABLE_FIELDS
from .activity_source_service import attach_stream
from .merge_alignment import align
from .merge_apply import ACTIVITY_FIELDS, STREAM_FIELDS, CopyMode, FieldRewriteCounts, NullPolicy, apply_merge_fields
from .merge_postprocess import recompute_activity_level_only, recompute_after_merge, threshold_fields_for_sports
from .models import Activity, Merge

_VALID_FIELDS = set(STREAM_FIELDS) | set(ACTIVITY_FIELDS)


class ActivitySummary(TypedDict):
    avg_power: int | None
    norm_power: int | None
    intensity: float | None
    tss: int


class MergeResult(TypedDict):
    merge_id: str | None  # None for a preview - nothing was recorded.
    offset_seconds: int
    alignment_score: float | None
    field_counts: dict[str, dict[str, int]]
    before: ActivitySummary
    after: ActivitySummary
    source_hidden: bool
    # Issue #503 (phase 1): set only when copy_mode="attach" actually attached a stream (never
    # on a preview, which attaches nothing). None for every "overwrite" merge.
    attached_source_id: str | None


def _is_multisport_linked(activity: Activity) -> bool:
    return activity.sport in ("multisport", "transition") or activity.parent_activity_id is not None


def _already_hidden_under(source: Activity, target: Activity) -> bool:
    """True when ``source`` is already hidden as a duplicate of exactly ``target``, e.g. marked that
    way by ingest-time overlap handling before the athlete came to merge the pair. Hiding it again
    would be a no-op, so the merge proceeds without hiding and without recording source_hidden: an
    undo then leaves the pre-existing mark alone instead of un-hiding something this merge never
    hid."""
    return source.primary_activity_id is not None and source.primary_activity_id == target.pk


def _validate(target: Activity, source: Activity, fields: list[str], hide_source: bool) -> None:
    if target.pk == source.pk:
        raise ValidationError({"source_id": "An activity cannot be merged with itself."})
    if target.athlete_id != source.athlete_id:
        raise ValidationError({"source_id": "Both activities must belong to the same athlete."})
    if not fields:
        raise ValidationError({"fields": "At least one field must be selected."})
    unknown = [f for f in fields if f not in _VALID_FIELDS]
    if unknown:
        raise ValidationError({"fields": f"Unknown field(s): {', '.join(unknown)}."})
    if hide_source:
        # Already a duplicate of *this* target (e.g. auto-marked at import) is fine - see
        # _already_hidden_under; only a duplicate of some other activity is refused.
        if source.primary_activity_id is not None and not _already_hidden_under(source, target):
            raise ValidationError({"source_id": "This activity is already a duplicate of another activity."})
        if _is_multisport_linked(source) or _is_multisport_linked(target):
            raise ValidationError({"source_id": "Multisport sessions and their legs cannot be hidden as duplicates."})


def _resolve_offset(target: Activity, source: Activity, offset_seconds: int | None) -> tuple[int, float | None]:
    """An explicit offset_seconds skips alignment entirely - its score is reported as None,
    since nothing was actually correlated."""
    if offset_seconds is not None:
        return offset_seconds, None
    initial_offset = round((source.start_date - target.start_date).total_seconds())
    target_records = list(target.records.values_list("t", "cadence", "power"))
    source_records = list(source.records.values_list("t", "cadence", "power"))
    result = align(target_records, source_records, initial_offset)
    return result.offset_seconds, result.score


def _summarize(activity: Activity) -> ActivitySummary:
    return {
        "avg_power": activity.avg_power,
        "norm_power": activity.norm_power,
        "intensity": activity.intensity,
        "tss": activity.tss,
    }


def _counts_as_dict(counts: dict[str, FieldRewriteCounts]) -> dict[str, dict[str, int]]:
    return {k: {"copied": v.copied, "kept_original": v.kept_original, "nulled": v.nulled} for k, v in counts.items()}


def _run_attach(
    target_id: str,
    source_id: str,
    fields: list[str],
    offset_seconds: int | None,
    hide_source: bool,
    preview: bool,
    source_kind: str | None,
) -> MergeResult:
    """Issue #503 (phases 1-3): copy_mode="attach" - never touches the target's existing primary
    stream for fields; delegates the actual computation/writing to activity_source_service
    .attach_stream. preview's own rollback is still wrapped in a transaction for symmetry with
    the overwrite path (and so hide_source's side effects roll back together with it), but -
    unlike overwrite - attach_stream itself writes nothing at all when preview=True, so there's
    nothing that specifically needs rolling back from attach's own writes. source_kind is only
    meaningful for fields=["heartrate"]/["cadence"] - ignored for fields=["power"]."""
    target = get_object_or_404(Activity, pk=target_id)
    source = get_object_or_404(Activity, pk=source_id)
    _validate(target, source, fields, hide_source)
    if len(fields) != 1 or fields[0] not in ATTACHABLE_FIELDS:
        raise ValidationError(
            {"fields": "copy_mode=attach only supports a single field of power, heartrate, or cadence."}
        )
    field = fields[0]
    before = _summarize(target)

    with transaction.atomic():
        result = attach_stream(target_id, source_id, field, offset_seconds, preview, source_kind)
        if not preview and hide_source and not _already_hidden_under(source, target):
            source.primary_activity = target
            source.save(update_fields=["primary_activity"])
            apply_duplicate_change(source, was_duplicate=False, is_duplicate=True)
        target.refresh_from_db()
        after = _summarize(target)
        if preview:
            transaction.set_rollback(True)

    counts = {field: FieldRewriteCounts(copied=result.copied, kept_original=0, nulled=result.nulled)}
    return {
        "merge_id": None,
        "offset_seconds": result.offset_seconds,
        "alignment_score": result.alignment_score,
        "field_counts": _counts_as_dict(counts),
        "before": before,
        "after": after,
        "source_hidden": hide_source and not preview,
        "attached_source_id": result.source_id,
    }


def run_merge(
    target_id: str,
    source_id: str,
    fields: list[str],
    null_policy: NullPolicy,
    offset_seconds: int | None = None,
    hide_source: bool = False,
    preview: bool = False,
    copy_mode: CopyMode = "overwrite",
    source_kind: str | None = None,
) -> MergeResult:
    if copy_mode == "attach":
        return _run_attach(target_id, source_id, fields, offset_seconds, hide_source, preview, source_kind)

    target = get_object_or_404(Activity, pk=target_id)
    source = get_object_or_404(Activity, pk=source_id)
    _validate(target, source, fields, hide_source)

    resolved_offset, score = _resolve_offset(target, source, offset_seconds)
    before = _summarize(target)
    # Snapshot each requested activity-level field's value before apply_merge_fields
    # overwrites it - see Merge.previous_activity_values' own docstring for why.
    previous_activity_values = {f: getattr(target, f) for f in fields if f in ACTIVITY_FIELDS}
    merge_row: Merge | None = None

    with transaction.atomic():
        counts = apply_merge_fields(target_id, source_id, fields, null_policy, resolved_offset)

        apply_hide = hide_source and not _already_hidden_under(source, target)
        if apply_hide:
            source.primary_activity = target
            source.save(update_fields=["primary_activity"])
            apply_duplicate_change(source, was_duplicate=False, is_duplicate=True)

        # Runs after hiding, not before, so the one post-processing pass (in particular its
        # full-athlete threshold ledger/TSS rebuild) already sees the source excluded as a
        # duplicate when hide_source is set - not a stale view from before the hide took effect.
        if preview:
            # A preview only needs the target's own re-derived stats for its before/after summary.
            # Running the athlete-wide threshold ledger/TSS rebuild inside it (then rolling it
            # back) made every preview as heavy as a real merge, and the merge dialog fires one
            # per field/offset change.
            recompute_activity_level_only(target_id)
        else:
            # Only the fields the merged activities' sports can define - a bike merge can't
            # change run thresholds. The source is included because hiding it removes its own
            # best efforts from its sport's ledger.
            recompute_after_merge(target_id, threshold_fields_for_sports(target.sport, source.sport))

        target.refresh_from_db()
        after = _summarize(target)

        if preview:
            # Rolls back to this call's own savepoint only, same as
            # athletes/threshold_history.py's manual-correction preview.
            transaction.set_rollback(True)
        else:
            merge_row = Merge.objects.create(
                target_activity=target,
                source_activity=source,
                fields=fields,
                null_policy=null_policy,
                offset_seconds=resolved_offset,
                alignment_score=score,
                field_counts=_counts_as_dict(counts),
                previous_activity_values=previous_activity_values,
                source_hidden=apply_hide,
            )

    return {
        "merge_id": merge_row.id if merge_row else None,
        "offset_seconds": resolved_offset,
        "alignment_score": score,
        "field_counts": _counts_as_dict(counts),
        "before": before,
        "after": after,
        "source_hidden": hide_source,
        "attached_source_id": None,
    }
