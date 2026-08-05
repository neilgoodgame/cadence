from typing import Any

from celery import shared_task
from django.core.files.storage import default_storage
from django.utils import timezone

from .export_writer import write_export
from .import_reader import read_import
from .models import ExportJob, ImportJob


@shared_task(bind=True, max_retries=0)  # type: ignore[untyped-decorator]
def run_export_task(self: Any, export_job_id: str, sport: str | None) -> None:
    job = ExportJob.objects.select_related("athlete").get(pk=export_job_id)
    job.status = "processing"
    job.save(update_fields=["status"])

    relative_path = f"exports/{job.athlete_id}/{job.id}.json.gz"
    try:
        size = write_export(job.athlete_id, sport, relative_path)
    except Exception as exc:
        job.status = "failed"
        job.error_message = str(exc)[:500]
        job.completed_at = timezone.now()
        job.save(update_fields=["status", "error_message", "completed_at"])
        return

    job.status = "ready"
    job.stored_path = relative_path
    job.file_size_bytes = size
    job.completed_at = timezone.now()
    job.save(update_fields=["status", "stored_path", "file_size_bytes", "completed_at"])


@shared_task(bind=True, max_retries=0)  # type: ignore[untyped-decorator]
def run_import_task(self: Any, import_job_id: str, stored_path: str) -> None:
    job = ImportJob.objects.get(pk=import_job_id)
    job.status = "processing"
    job.save(update_fields=["status"])

    try:
        counts = read_import(job.athlete_id, stored_path)
    except Exception as exc:
        job.status = "failed"
        job.error_message = str(exc)[:500]
        job.completed_at = timezone.now()
        job.save(update_fields=["status", "error_message", "completed_at"])
        return
    finally:
        # The staged upload is never needed again once the job reaches a terminal status,
        # success or failure - don't accumulate multi-GB import files on disk.
        try:
            default_storage.delete(stored_path)
        except Exception:
            pass

    job.status = "ready"
    for key, value in counts.items():
        setattr(job, key, value)
    job.completed_at = timezone.now()
    job.save(
        update_fields=[
            "status",
            "completed_at",
            *counts.keys(),
        ]
    )
