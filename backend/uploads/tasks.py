import logging
from typing import Any

from celery import shared_task
from django.utils import timezone

from activities.serializers import ActivitySerializer
from webhooks.events import fire_event

from .models import Upload, UploadBatch
from .processing import (
    UploadProcessingError,
    ingest_upload,
    recompute_after_duplicate_change,
    threshold_fields_affected_by_demoted_duplicate,
)
from .serializers import UploadBatchSerializer

logger = logging.getLogger(__name__)


@shared_task(bind=True, max_retries=3)  # type: ignore[untyped-decorator]
def process_upload(self: Any, upload_id: str) -> None:
    upload = Upload.objects.select_related("athlete").get(pk=upload_id)
    upload.status = "processing"
    upload.save(update_fields=["status"])

    try:
        activity = ingest_upload(upload)
    except UploadProcessingError as exc:
        # Garmin account exports mix metadata-stub FITs (no activity data) in with real
        # activities; failing them would drown a bulk import in errors, so batch children
        # are skipped instead. A deliberate single-file upload still fails loudly.
        if exc.code == "no_activity_data" and upload.batch_id:
            upload.status = "skipped"
        else:
            upload.status = "failed"
            upload.error_code = exc.code
            upload.error_message = exc.message
        upload.completed_at = timezone.now()
        upload.save(update_fields=["status", "error_code", "error_message", "completed_at"])
    else:
        upload.status = "ready"
        upload.activity = activity
        upload.progress = 1.0
        upload.completed_at = timezone.now()
        upload.save(update_fields=["status", "activity", "progress", "completed_at"])
        fire_event("activity.created", upload.athlete_id, ActivitySerializer(activity).data)
        if upload.overlap_action == "marked" and not upload.batch_id:
            _recompute_after_ingest_mark(upload, activity)

    if upload.batch_id:
        _maybe_finalize_batch(upload.batch_id)


def _recompute_after_ingest_mark(upload: Upload, activity: Any) -> None:
    """Runs after the upload is already ready (see processing.ingest_upload's comment for why it
    isn't inline). Scoped to the threshold fields the demoted duplicate actually sourced; the
    new activity, if it is the demoted side, was never a ledger source, so nothing is rebuilt.
    Failures are logged, never raised: the upload itself has already succeeded."""
    try:
        activity.refresh_from_db(fields=["primary_activity"])
        demoted_id = upload.overlap_other_activity_id if activity.primary_activity_id is None else None
        fields = threshold_fields_affected_by_demoted_duplicate(demoted_id, activity.sport) if demoted_id else []
        # Issue #545: the recompute can start at the demoted activity's own date - its duplicate
        # status is the only thing that changed. A missing activity (deleted in the meantime)
        # falls back to the full rebuild via from_date=None.
        from_date = None
        if demoted_id:
            from activities.models import Activity

            demoted = Activity.objects.filter(pk=demoted_id).values_list("start_date", flat=True).first()
            from_date = demoted.date() if demoted else None
        recompute_after_duplicate_change(upload.athlete, fields, from_date=from_date)
    except Exception:
        logger.exception("Post-upload duplicate recompute failed for upload %s", upload.pk)


def _maybe_finalize_batch(batch_id: str) -> None:
    batch = UploadBatch.objects.get(pk=batch_id)
    if batch.uploads.filter(status__in=["queued", "processing"]).exists():
        return
    # Issue #498: runs once for the whole batch, rather than once per marked file (see
    # processing.ingest_upload's own call site, skipped for a batch child precisely so this is
    # the one place it happens).
    if batch.uploads.filter(overlap_action="marked").exists():
        # Issue #545: one recompute from the earliest marked upload's activity date - a
        # duplicate-status change can only alter the ledger from that date onward. A marked
        # upload with no attached activity (shouldn't normally happen) falls back to the full
        # rebuild via from_date=None, same as recompute_after_duplicate_change's own default.
        marked_dates = list(
            batch.uploads.filter(overlap_action="marked", activity__isnull=False).values_list(
                "activity__start_date", flat=True
            )
        )
        from_date = min(marked_dates).date() if marked_dates else None
        recompute_after_duplicate_change(batch.athlete, from_date=from_date)
    batch.status = "completed"
    batch.completed_at = timezone.now()
    batch.save(update_fields=["status", "completed_at"])
    fire_event("upload_batch.completed", batch.athlete_id, UploadBatchSerializer(batch).data)
