"""Issue #501 (phase 1, part 5): undoes a merge - restores the target's original field values
and un-hides the source if the merge hid it.

Stream fields are restored by re-parsing the target's own stored upload file - the exact file
ingest itself parsed, so its samples are the authoritative "what this activity originally
recorded" and share the target's own timeline exactly (no alignment/offset needed, unlike a
merge itself). Activity-level fields are restored from the snapshot merge_service.run_merge took
immediately before overwriting them - re-deriving them instead (e.g. regenerating `name` from
sport+date) would silently discard any legitimate edit made between the merge and this undo.

Disallowed when the target's upload row has no stored file (legacy data, or an export restore
with no original file) - the same open question #501 itself resolved. Mirrors backend_java's
ActivityUnmergeService - keep the two in sync when changing either.
"""

from django.core.files.storage import default_storage
from django.db import transaction
from django.shortcuts import get_object_or_404
from django.utils import timezone

from core.exceptions import UnprocessableEntityError
from gear.mileage import apply_duplicate_change
from uploads.models import Upload
from uploads.parsers import parse_file

from .merge_apply import ACTIVITY_FIELDS, STREAM_FIELDS
from .merge_postprocess import recompute_after_merge
from .models import Activity, Merge, Record


def _latest_active_merge(target_id: str) -> Merge:
    """Restoring stream fields re-parses the target's *original* upload file - correct for a
    single merge, but wrong for a chain of two-or-more merges that touched the same field (undoing
    the second should land on the first merge's result, not on the pre-every-merge original). Since
    nothing here tracks per-merge snapshots of stream data (that's the whole reason undo re-parses
    rather than storing them), a target with more than one active merge can't be safely undone at
    all - block it loudly rather than silently restoring too far back."""
    active = list(Merge.objects.filter(target_activity_id=target_id, undone_at__isnull=True).order_by("-created_at"))
    if not active:
        raise UnprocessableEntityError("This activity has no active merge to undo.")
    if len(active) > 1:
        raise UnprocessableEntityError(
            "This activity has more than one merge applied - undo isn't supported for a merge chain."
        )
    return active[0]


def _resolve_upload(target: Activity) -> Upload:
    upload = Upload.objects.filter(activity=target).exclude(stored_path="").first()
    if upload is None:
        raise UnprocessableEntityError("This merge can't be undone: no original file stored.")
    return upload


def undo_merge(target_id: str) -> Merge:
    target = get_object_or_404(Activity, pk=target_id)
    merge = _latest_active_merge(target_id)
    upload = _resolve_upload(target)

    # ingest_upload links Upload.activity only to the parent/sole activity, never a multisport
    # leg (see its own docstring) - a target reachable past _resolve_upload above can never BE
    # one, so parsed_activities[0] (the parent/sole parse result) is always the right one.
    parsed_activities = parse_file(default_storage.path(upload.stored_path), upload.filename)
    samples_by_t = {s["t"]: s for s in parsed_activities[0]["samples"]}

    stream_fields = [f for f in merge.fields if f in STREAM_FIELDS]
    activity_fields = [f for f in merge.fields if f in ACTIVITY_FIELDS]

    with transaction.atomic():
        if stream_fields:
            target_rows = list(target.records.only("t", *stream_fields))
            changed_rows = []
            for row in target_rows:
                original = samples_by_t.get(row.t)
                changed = False
                for field in stream_fields:
                    original_value = original.get(field) if original else None
                    if getattr(row, field) != original_value:
                        setattr(row, field, original_value)
                        changed = True
                if changed:
                    changed_rows.append(row)
            if changed_rows:
                Record.objects.bulk_update(changed_rows, stream_fields)

        if activity_fields:
            for field in activity_fields:
                setattr(target, field, merge.previous_activity_values.get(field))
            target.save(update_fields=activity_fields)

        source = None
        if merge.source_hidden and merge.source_activity_id is not None:
            source = Activity.objects.filter(pk=merge.source_activity_id).first()
            # Only un-hide if it's still pointing at this exact target - if something else
            # re-resolved it since (a later mark/unmark/different merge), leave it alone rather
            # than clobber that newer state, the same caution undo_batch's own primary-check
            # already applies.
            if source is not None and source.primary_activity_id == target.id:
                source.primary_activity = None
                source.save(update_fields=["primary_activity"])
                apply_duplicate_change(source, was_duplicate=True, is_duplicate=False)
            else:
                source = None

        recompute_after_merge(target_id)
        if source is not None:
            # recompute_after_merge(target_id) already reran the full-athlete threshold
            # ledger/TSS cascade, which now sees source un-excluded - but duration curve/best
            # efforts/workout-match are per-activity and were never run for source itself.
            recompute_after_merge(source.id)

        merge.undone_at = timezone.now()
        merge.save(update_fields=["undone_at"])

    return merge
