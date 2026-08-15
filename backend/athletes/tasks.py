from collections.abc import Callable
from typing import Any

from celery import shared_task
from django.utils import timezone

from accounts.models import User
from activities.models import Activity, BestEffort

from .models import BestEffortRecomputeJob

_SPORT_FOR_KIND = {
    "cycling_hr": "bike",
    "cycling_power": "bike",
    "running_hr": "run",
    "running_pace": "run",
    "running_power": "run",
}


def recompute_best_efforts(
    athlete: User,
    kind: str | None,
    on_total: Callable[[int], None],
    on_progress: Callable[[int], None],
) -> int:
    """Deletes and rebuilds an athlete's BestEffort rows for `kind` (or every kind, if
    None). Returns the number of activities processed. Extracted from the original
    synchronous StreamingHttpResponse generator (see git history of athletes/views.py's
    _recompute_stream) so the same logic can run inside a Celery task instead - a full
    account (thousands of activities) can take longer than gunicorn's sync-worker
    timeout, which streaming alone doesn't avoid."""
    from uploads.processing import compute_kind_best_efforts, update_best_efforts

    qs = BestEffort.objects.filter(athlete=athlete)
    if kind:
        qs = qs.filter(kind=kind)
    qs.delete()

    candidates = Activity.objects.filter(
        athlete=athlete,
        parent_activity__isnull=True,
    ).exclude(sport__in=("multisport", "transition"))
    if kind:
        candidates = candidates.filter(sport=_SPORT_FOR_KIND[kind])

    activities = list(candidates.order_by("start_date"))
    total = len(activities)
    on_total(total)

    for i, activity in enumerate(activities):
        records = list(activity.records.order_by("t"))
        if records:
            power_series = [r.power for r in records]
            hr_series = [r.heartrate for r in records]
            t_series = [r.t for r in records]
            distance_series = [r.distance_km for r in records]
            if kind:
                compute_kind_best_efforts(activity, athlete, kind, power_series, t_series, distance_series, hr_series)
            else:
                update_best_efforts(activity, athlete, power_series, t_series, distance_series, hr_series)
        on_progress(i + 1)

    return total


@shared_task(bind=True, max_retries=0)  # type: ignore[untyped-decorator]
def run_best_effort_recompute(self: Any, job_id: str) -> None:
    job = BestEffortRecomputeJob.objects.select_related("athlete").get(pk=job_id)
    job.status = "processing"
    job.save(update_fields=["status"])

    def on_total(total: int) -> None:
        BestEffortRecomputeJob.objects.filter(pk=job.id).update(total_items=total)

    def on_progress(processed: int) -> None:
        BestEffortRecomputeJob.objects.filter(pk=job.id).update(processed_items=processed)

    try:
        recompute_best_efforts(job.athlete, job.kind or None, on_total, on_progress)
    except Exception as exc:
        job.status = "failed"
        job.error_message = str(exc)[:500]
        job.completed_at = timezone.now()
        job.save(update_fields=["status", "error_message", "completed_at"])
        return

    job.status = "ready"
    job.completed_at = timezone.now()
    job.save(update_fields=["status", "completed_at"])
