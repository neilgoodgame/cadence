from celery import shared_task
from django.utils import timezone

from activities.serializers import ActivitySerializer
from webhooks.events import fire_event

from .models import Upload, UploadBatch
from .processing import UploadProcessingError, ingest_upload
from .serializers import UploadBatchSerializer


@shared_task(bind=True, max_retries=3)
def process_upload(self, upload_id):
    upload = Upload.objects.select_related("athlete").get(pk=upload_id)
    upload.status = "processing"
    upload.save(update_fields=["status"])

    try:
        activity = ingest_upload(upload)
    except UploadProcessingError as exc:
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

    if upload.batch_id:
        _maybe_finalize_batch(upload.batch_id)


def _maybe_finalize_batch(batch_id):
    batch = UploadBatch.objects.get(pk=batch_id)
    if batch.uploads.filter(status__in=["queued", "processing"]).exists():
        return
    batch.status = "completed"
    batch.completed_at = timezone.now()
    batch.save(update_fields=["status", "completed_at"])
    fire_event("upload_batch.completed", batch.athlete_id, UploadBatchSerializer(batch).data)
