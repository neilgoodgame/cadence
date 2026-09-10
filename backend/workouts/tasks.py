from typing import Any

from celery import shared_task
from django.utils import timezone

from .match_scan import run_match_scan
from .models import WorkoutMatchScan


@shared_task(bind=True, max_retries=0)  # type: ignore[untyped-decorator]
def run_workout_match_scan_task(self: Any, scan_id: str) -> None:
    scan = WorkoutMatchScan.objects.select_related("workout", "workout__created_by").get(pk=scan_id)
    scan.status = "processing"
    scan.save(update_fields=["status"])

    try:
        run_match_scan(scan)
    except Exception as exc:
        scan.status = "failed"
        scan.error_message = str(exc)[:500]
        scan.completed_at = timezone.now()
        scan.save(update_fields=["status", "error_message", "completed_at"])
        return

    scan.status = "ready"
    scan.completed_at = timezone.now()
    scan.save(update_fields=["status", "completed_at"])
