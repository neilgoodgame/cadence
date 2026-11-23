"""Issue #540 (phase 6): applies/undoes a StreamPatch - rewriting the activity's primary stream
for field, at explicit or live-detected segments, with from_source's own stored reading there.
See stream_flag_service.py for the read-only detection side this module calls back into for its
own `segments: "flagged"` case; see models.StreamPatch's own docstring for why this always
targets the primary stream and never a stored ActivitySource.

Reuses merge_apply._compute_stream_field's segment-restricted rewrite and merge_service.py's own
preview-via-rollback technique (apply for real, then roll back the whole transaction when
preview=True) rather than inventing either a second copy routine or a second preview mechanism.
Mirrors backend_java's StreamPatchService - keep the two in sync when changing either.
"""

import dataclasses
from dataclasses import dataclass
from typing import Literal

from django.db import transaction
from django.shortcuts import get_object_or_404
from django.utils import timezone
from rest_framework.exceptions import ValidationError

from core.exceptions import ConflictError, UnprocessableEntityError

from .activity_post_processing import recompute_activity_level_only, recompute_after_merge
from .activity_source_service import VALID_FIELDS
from .merge_apply import FieldRewriteCounts, compute_stream_field
from .models import Activity, ActivityStream, Record, StreamPatch
from .stream_flag_service import detect_stream_flags

# A patch always applies what it's given - there's no "keep original where the source has
# nothing" choice the way a merge has, since the whole point of calling this is "replace this
# implausible stretch", not "fill gaps".
_NULL_POLICY = "overwrite"
# Repair targets a glitch run, not a whole-stream rewrite (that's merge/attach territory) - a
# bigger explicit request is almost certainly a caller bug. segments="flagged" is exempt - a
# detector's own run-length caps already bound it. Untested guess, flag in the PR.
_MAX_EXPLICIT_SEGMENTS_SECONDS = 3600

Segments = list[tuple[int, int]] | Literal["flagged"]


@dataclass(frozen=True)
class StreamPatchResult:
    patch_id: str | None  # None for a preview - nothing was created
    field: str
    from_source: str
    detected_kind: str | None
    # The segments actually applied - always echoed back (not just a count), so a preview and
    # its later commit can never silently diverge if segments="flagged" and the underlying data
    # changes between the two calls. See models.StreamPatch's own "flagged-segment transparency"
    # note.
    segments: list[tuple[int, int]]
    counts: FieldRewriteCounts


def _validate_field(field: str) -> None:
    if field not in VALID_FIELDS:
        raise ValidationError({"field": "field must be 'power', 'heartrate', or 'cadence'."})


def _validate_explicit_segments(segments: list[tuple[int, int]]) -> None:
    for start, end in segments:
        if end < start:
            raise ValidationError({"segments": f"Segment [{start}, {end}] has end before start."})
    total = sum(end - start + 1 for start, end in segments)
    if total > _MAX_EXPLICIT_SEGMENTS_SECONDS:
        raise ValidationError(
            {
                "segments": f"Explicit segments total {total}s, more than the "
                f"{_MAX_EXPLICIT_SEGMENTS_SECONDS}s repair cap - that's whole-stream-rewrite "
                "territory (merge/attach), not a repair."
            }
        )


def _resolve_segments(
    activity_id: str, field: str, segments: Segments, kind: str | None, reference: str | None
) -> tuple[list[tuple[int, int]], str | None]:
    """Returns (resolved segments, detected_kind for the StreamPatch row)."""
    if segments == "flagged":
        if kind is None:
            raise ValidationError({"kind": "kind is required when segments='flagged'."})
        flags = detect_stream_flags(activity_id, field, kind, reference)
        return [(s.start_t, s.end_t) for s in flags.segments], kind
    _validate_explicit_segments(segments)
    return segments, None


def _reject_overlap(activity_id: str, field: str, segments: list[tuple[int, int]]) -> None:
    """A patch is rejected when its segments overlap any *active* (undone_at IS NULL)
    StreamPatch on the same activity+field - without this, a second patch's previous_values
    would snapshot the first patch's already-patched values and unpatch order would matter
    (LIFO restores correctly; the reverse writes stale values over a still-active patch).
    Rejecting overlaps up front makes unpatch order-independent."""
    active = StreamPatch.objects.filter(activity_id=activity_id, field=field, undone_at__isnull=True)
    for existing in active:
        for e_start, e_end in existing.segments:
            if any(start <= e_end and e_start <= end for start, end in segments):
                raise ConflictError(f"Overlaps active patch '{existing.id}' on this activity/field.")


def patch_stream(
    activity_id: str,
    field: str,
    from_source: str,
    segments: Segments,
    kind: str | None = None,
    reference: str | None = None,
    preview: bool = False,
) -> StreamPatchResult:
    _validate_field(field)
    activity = get_object_or_404(Activity, pk=activity_id)
    source_stream = get_object_or_404(ActivityStream, activity_id=activity_id, field=field, source_id=from_source)

    resolved_segments, detected_kind = _resolve_segments(activity_id, field, segments, kind, reference)
    _reject_overlap(activity_id, field, resolved_segments)

    target_rows = list(activity.records.only("t", field))
    target_values = {row.t: getattr(row, field) for row in target_rows}
    source_values = {source_stream.t0 + i: v for i, v in enumerate(source_stream.values)}

    new_values, counts = compute_stream_field(
        target_values, source_values, offset_seconds=0, null_policy=_NULL_POLICY, segments=resolved_segments
    )
    patched_ts = {t for t in target_values if any(start <= t <= end for start, end in resolved_segments)}
    previous_values = {str(t): target_values[t] for t in patched_ts}

    patch: StreamPatch | None = None
    with transaction.atomic():
        changed_rows = []
        for row in target_rows:
            if row.t in patched_ts and getattr(row, field) != new_values[row.t]:
                setattr(row, field, new_values[row.t])
                changed_rows.append(row)
        if changed_rows:
            Record.objects.bulk_update(changed_rows, [field])

        if preview:
            # Same reasoning as merge_service.run_merge's own preview: the lighter
            # activity-level-only recompute, not the full athlete-wide cascade, since a preview
            # only needs the target's own re-derived stats and the patch dialog fires one per
            # segment/source change.
            recompute_activity_level_only(activity_id)
            transaction.set_rollback(True)
        else:
            recompute_after_merge(activity_id)
            patch = StreamPatch.objects.create(
                activity_id=activity_id,
                field=field,
                from_source_id=from_source,
                detected_kind=detected_kind,
                segments=[list(s) for s in resolved_segments],
                previous_values=previous_values,
            )

    return StreamPatchResult(
        patch_id=patch.id if patch else None,
        field=field,
        from_source=from_source,
        detected_kind=detected_kind,
        segments=resolved_segments,
        counts=counts,
    )


def stream_patch_response(result: StreamPatchResult) -> dict:
    return {
        "patch_id": result.patch_id,
        "field": result.field,
        "from_source": result.from_source,
        "detected_kind": result.detected_kind,
        "segments": [list(s) for s in result.segments],
        "counts": dataclasses.asdict(result.counts),
    }


def _active_patch(activity_id: str, patch_id: str | None) -> StreamPatch:
    """Mirrors unmerge_service._latest_active_merge's exact ambiguity-handling shape: patch_id
    picks a specific active patch directly; with no patch_id, exactly one active patch on this
    activity must exist, or the request is ambiguous - blocked loudly (naming the available ids)
    rather than guessing which one was meant."""
    if patch_id is not None:
        patch = StreamPatch.objects.filter(pk=patch_id, activity_id=activity_id, undone_at__isnull=True).first()
        if patch is None:
            raise UnprocessableEntityError(f"No active patch '{patch_id}' found on this activity.")
        return patch
    active = list(StreamPatch.objects.filter(activity_id=activity_id, undone_at__isnull=True).order_by("-created_at"))
    if not active:
        raise UnprocessableEntityError("This activity has no active patch to undo.")
    if len(active) > 1:
        ids = ", ".join(p.id for p in active)
        raise UnprocessableEntityError(
            f"This activity has more than one active patch ({ids}) - pass patch_id to choose which one to undo."
        )
    return active[0]


def unpatch_stream(activity_id: str, patch_id: str | None = None) -> StreamPatch:
    """Restores Record's field values at the patch's segments from previous_values, sets
    undone_at, and re-runs the recompute cascade. The StreamPatch row itself is kept (not
    deleted) - see models.StreamPatch's own "accepted consequence" note on why this leaves
    from_source permanently PROTECTed even after undo."""
    patch = _active_patch(activity_id, patch_id)
    with transaction.atomic():
        rows_by_t = {
            row.t: row
            for row in Record.objects.filter(activity_id=activity_id, t__in=[int(t) for t in patch.previous_values])
        }
        changed_rows = []
        for t_str, original_value in patch.previous_values.items():
            row = rows_by_t.get(int(t_str))
            if row is not None and getattr(row, patch.field) != original_value:
                setattr(row, patch.field, original_value)
                changed_rows.append(row)
        if changed_rows:
            Record.objects.bulk_update(changed_rows, [patch.field])
        patch.undone_at = timezone.now()
        patch.save(update_fields=["undone_at"])
        recompute_after_merge(activity_id)
    return patch
