"""Issue #498's resolve_duplicates_batch/undo_batch MCP tools: applies mark/merge to many
overlap pairs at once - either an explicit list, or everything the overlap query finds - with a
dry-run preview (the default) and, once actually applied, a persisted DuplicateResolutionBatch
so the whole thing can be undone later. Mirrors backend_java's DuplicateResolutionBatchService -
keep the two in sync when changing either.
"""

from datetime import date
from typing import Any

from django.shortcuts import get_object_or_404
from django.utils import timezone
from rest_framework.exceptions import ValidationError

from accounts.models import User
from gear.mileage import apply_duplicate_change

from .models import Activity, DuplicateResolutionBatch, DuplicateResolutionEntry
from .overlap_detection import preview_resolution, resolve_pair
from .overlaps import find_overlaps
from .views import _recompute_after_duplicate_change

# A deliberately bounded, one-shot action - unlike the backfill command (meant to walk an
# athlete's entire history unattended), this keeps a single MCP call's cost, and the undo
# surface it creates, small and reviewable.
MAX_BATCH_PAIRS = 100


def _validated_explicit_pairs(athlete: User, pairs: list[list[str]]) -> list[tuple[str, str]]:
    if not pairs:
        raise ValidationError({"pairs": "Must be a non-empty list of [activity_id, other_activity_id] pairs."})
    if len(pairs) > MAX_BATCH_PAIRS:
        raise ValidationError({"pairs": f"At most {MAX_BATCH_PAIRS} pairs per batch."})
    validated = []
    for pair in pairs:
        if len(pair) != 2:
            raise ValidationError({"pairs": "Each pair must have exactly 2 activity ids."})
        activity_id, other_id = pair
        # Ownership check - a pair's ids must belong to the effective athlete, same as every
        # other duplicate-resolution path (resolve_pair itself trusts its caller already did
        # this, same as detect_and_resolve_overlap/backfill_overlaps trust the overlap query's
        # own athlete scoping).
        get_object_or_404(Activity, pk=activity_id, athlete=athlete)
        get_object_or_404(Activity, pk=other_id, athlete=athlete)
        validated.append((activity_id, other_id))
    return validated


def _queried_pairs(
    athlete: User,
    *,
    activity_id: str | None,
    sport: str | None,
    cross_sport: bool,
    device: str | None,
    other_device: str | None,
    environment: str | None,
    from_date: date | None,
    to_date: date | None,
    min_duration_seconds: int | None,
    other_has: list[str] | None,
    min_overlap_pct: float | None,
    max_start_delta_seconds: int | None,
    limit: int | None,
) -> list[tuple[str, str]]:
    page = find_overlaps(
        athlete.id,
        activity_id=activity_id,
        sport=sport,
        cross_sport=cross_sport,
        device=device,
        other_device=other_device,
        environment=environment,
        from_date=from_date,
        to_date=to_date,
        min_duration_seconds=min_duration_seconds,
        other_has=other_has,
        min_overlap_pct=min_overlap_pct,
        max_start_delta_seconds=max_start_delta_seconds,
        # Resolving an already-resolved pair makes no sense - forced regardless of caller input.
        include_resolved=False,
        limit=min(limit or MAX_BATCH_PAIRS, MAX_BATCH_PAIRS),
    )
    return [(pair["anchor"]["id"], pair["other"]["id"]) for pair in page["data"]]


def resolve_duplicates_batch(
    athlete: User,
    action: str,
    *,
    pairs: list[list[str]] | None = None,
    activity_id: str | None = None,
    sport: str | None = None,
    cross_sport: bool = False,
    device: str | None = None,
    other_device: str | None = None,
    environment: str | None = None,
    from_date: date | None = None,
    to_date: date | None = None,
    min_duration_seconds: int | None = None,
    other_has: list[str] | None = None,
    min_overlap_pct: float | None = None,
    max_start_delta_seconds: int | None = None,
    limit: int | None = None,
    dry_run: bool = True,
    set_by: User | None = None,
) -> dict[str, Any]:
    """Applies `action` ("mark" or "merge" - merge currently falls back to flag everywhere,
    same as every other overlap-resolution path, until #501 ships) to every pair in `pairs`, or
    every unresolved pair the overlap query's own filter kwargs find (same parameters as
    list_overlapping_activities), bounded to MAX_BATCH_PAIRS either way.

    `dry_run` (default True) only ever reports what *would* happen - nothing is written, and no
    DuplicateResolutionBatch is created (there'd be nothing to undo). `dry_run=False` actually
    resolves every pair, recomputes the athlete's derived stats once at the end (not once per
    pair), and persists a DuplicateResolutionBatch + one DuplicateResolutionEntry per pair so
    undo_batch can reverse it later.
    """
    if action not in ("mark", "merge"):
        raise ValidationError({"action": "Must be 'mark' or 'merge'."})

    query_filters_given = any(
        [
            activity_id,
            sport,
            cross_sport,
            device,
            other_device,
            environment,
            from_date,
            to_date,
            min_duration_seconds,
            other_has,
            min_overlap_pct,
            max_start_delta_seconds,
        ]
    )
    if pairs is not None and query_filters_given:
        raise ValidationError({"pairs": "Pass either pairs or the overlap query's filters, not both."})

    if pairs is not None:
        candidate_pairs = _validated_explicit_pairs(athlete, pairs)
    else:
        candidate_pairs = _queried_pairs(
            athlete,
            activity_id=activity_id,
            sport=sport,
            cross_sport=cross_sport,
            device=device,
            other_device=other_device,
            environment=environment,
            from_date=from_date,
            to_date=to_date,
            min_duration_seconds=min_duration_seconds,
            other_has=other_has,
            min_overlap_pct=min_overlap_pct,
            max_start_delta_seconds=max_start_delta_seconds,
            limit=limit,
        )

    if dry_run:
        results = [
            {"activity_id": anchor_id, **preview_resolution(anchor_id, other_id, action)}
            for anchor_id, other_id in candidate_pairs
        ]
        return {"dry_run": True, "batch_id": None, "results": results}

    batch = DuplicateResolutionBatch.objects.create(athlete=athlete, action=action, created_by=set_by)
    results = []
    affected = False
    for anchor_id, other_id in candidate_pairs:
        resolution = resolve_pair(anchor_id, other_id, action)
        DuplicateResolutionEntry.objects.create(
            batch=batch,
            activity_id=anchor_id,
            other_activity_id=other_id,
            outcome=resolution["action"],
            primary_activity_id=resolution["primary_activity_id"],
        )
        results.append({"activity_id": anchor_id, **resolution})
        if resolution["action"] == "marked":
            affected = True

    if affected:
        _recompute_after_duplicate_change(athlete)

    return {"dry_run": False, "batch_id": batch.id, "results": results}


def undo_batch(athlete: User, batch_id: str) -> dict[str, Any]:
    """Reverses a DuplicateResolutionBatch's "marked" entries (a "flagged" entry never changed
    anything, so there's nothing to reverse for it) - unmarking each duplicate exactly like
    unmark_duplicate does, including restoring its gear mileage. An entry is skipped, not
    forced, if its activity's primary_activity_id no longer matches what this batch set it to -
    it was re-resolved or hand-edited since, and blindly reverting it could clobber that."""
    batch = get_object_or_404(DuplicateResolutionBatch, pk=batch_id, athlete=athlete)
    if batch.undone_at is not None:
        raise ValidationError({"batch_id": "This batch has already been undone."})

    reverted = 0
    skipped = 0
    affected = False
    for entry in batch.entries.filter(outcome="marked").select_related("activity"):
        activity = entry.activity
        if activity.primary_activity_id != entry.primary_activity_id:
            skipped += 1
            continue
        activity.primary_activity = None
        activity.save(update_fields=["primary_activity"])
        apply_duplicate_change(activity, was_duplicate=True, is_duplicate=False)
        reverted += 1
        affected = True

    batch.undone_at = timezone.now()
    batch.save(update_fields=["undone_at"])

    if affected:
        _recompute_after_duplicate_change(athlete)

    return {"batch_id": batch.id, "reverted_count": reverted, "skipped_count": skipped}
