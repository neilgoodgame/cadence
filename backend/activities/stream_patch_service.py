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

from django.db import connection, transaction
from django.shortcuts import get_object_or_404
from django.utils import timezone
from rest_framework.exceptions import ValidationError

from core.exceptions import ConflictError, UnprocessableEntityError

from .activity_post_processing import recompute_activity_level_only, recompute_after_merge
from .activity_source_service import validate_field
from .merge_apply import FieldRewriteCounts, compute_stream_field
from .models import Activity, ActivityStream, Record, StreamPatch
from .stream_flag_service import detect_stream_flags

# A patch always applies what it's given - there's no "keep original where the source has
# nothing" choice the way a merge has, since the whole point of calling this is "replace this
# implausible stretch", not "fill gaps".
_NULL_POLICY = "overwrite"
# Repair targets a glitch run, not a whole-stream rewrite (that's merge/attach territory) - a
# bigger request is almost certainly a caller bug. Enforced on segments="flagged" too, not just
# explicit input - contact_loss's two detectors each already cap an individual run's length, but
# cadence_lock_detection's merged runs have no length cap of their own, so "flagged" output can't
# be assumed pre-bounded for every kind. Untested guess, flag in the PR.
_MAX_SEGMENTS_SECONDS = 3600

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


def _validate_segments(segments: list[tuple[int, int]]) -> None:
    for start, end in segments:
        if end < start:
            raise ValidationError({"segments": f"Segment [{start}, {end}] has end before start."})
    total = sum(end - start + 1 for start, end in segments)
    if total > _MAX_SEGMENTS_SECONDS:
        raise ValidationError(
            {
                "segments": f"Segments total {total}s, more than the "
                f"{_MAX_SEGMENTS_SECONDS}s repair cap - that's whole-stream-rewrite "
                "territory (merge/attach), not a repair."
            }
        )


def _resolve_segments(
    activity_id: str, field: str, segments: Segments, kind: str | None, reference: str | None
) -> tuple[list[tuple[int, int]], str | None]:
    """Returns (resolved segments, detected_kind for the StreamPatch row). The length cap is
    enforced on the resolved segments regardless of path - see _MAX_SEGMENTS_SECONDS' own
    comment for why "flagged" isn't exempt."""
    if segments == "flagged":
        if kind is None:
            raise ValidationError({"kind": "kind is required when segments='flagged'."})
        flags = detect_stream_flags(activity_id, field, kind, reference)
        resolved, detected_kind = [(s.start_t, s.end_t) for s in flags.segments], kind
    else:
        resolved, detected_kind = segments, None
    _validate_segments(resolved)
    return resolved, detected_kind


def _acquire_patch_lock(activity_id: str, field: str) -> None:
    """Code review follow-up on issue #540's own PR: closes the TOCTOU race between
    _reject_overlap's read and the later StreamPatch.objects.create - without this, two
    concurrent patch_stream calls on the same activity/field could both pass the overlap check
    before either writes, breaking the "unpatch is order-independent" invariant the overlap
    check exists to guarantee. A transaction-scoped Postgres advisory lock, keyed by a stable
    hash of (activity_id, field) computed server-side (hashtextextended - not Python's own
    hash(), which PYTHONHASHSEED makes unstable across processes and so useless as a lock key),
    serializes concurrent callers on the same key without a schema change. Released
    automatically at commit or rollback, so preview's own transaction.set_rollback(True) still
    releases it correctly. Must be called from inside the same transaction that does the
    overlap check and the write - a lock taken in its own separate transaction would release
    before that work even starts."""
    with connection.cursor() as cursor:
        cursor.execute("SELECT pg_advisory_xact_lock(hashtextextended(%s, 0))", [f"{activity_id}:{field}"])


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
    validate_field(field)
    activity = get_object_or_404(Activity, pk=activity_id)
    source_stream = get_object_or_404(ActivityStream, activity_id=activity_id, field=field, source_id=from_source)

    resolved_segments, detected_kind = _resolve_segments(activity_id, field, segments, kind, reference)

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
        # The lock must come before the overlap check, in the same transaction as the write
        # below - see _acquire_patch_lock's own docstring.
        _acquire_patch_lock(activity_id, field)
        _reject_overlap(activity_id, field, resolved_segments)

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
