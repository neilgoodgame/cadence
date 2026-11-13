from collections.abc import Callable
from typing import Any

from celery import shared_task
from django.utils import timezone

from accounts.models import User
from activities.models import Activity, BestEffort

from .models import BestEffortRecomputeJob, RunningPowerSourceRecomputeJob

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


def recompute_running_power_source(
    athlete: User, on_total: Callable[[int], None], on_progress: Callable[[int], None]
) -> tuple[int, int]:
    """Issue #503 (phase 2): the bulk side of running-power re-designation - finds every run
    whose stored power_source no longer matches the athlete's *current* running_power_source
    preference, swaps each one (reusing activity_source_service._swap_primary_stream) if it has
    a stored alternate to swap to, then runs the shared athlete-wide recompute cascade
    (merge_postprocess.recompute_after_duplicate_change) exactly once at the end - not once per
    swapped activity, which would be quadratic in the athlete's activity count (see
    merge_postprocess.recompute_activity_level_only's own docstring). Returns (swapped, skipped).
    """
    from activities.activity_source_service import _swap_primary_stream
    from activities.merge_postprocess import recompute_activity_level_only, recompute_after_duplicate_change
    from activities.models import Activity, ActivityStream

    preference = athlete.running_power_source
    mismatched = list(
        Activity.objects.filter(athlete=athlete, sport="run")
        .exclude(power_source="")
        .exclude(power_source=preference)
        .order_by("start_date")
    )
    total = len(mismatched)
    on_total(total)

    swapped = skipped = 0
    # Issue #545: the cascade at the end can start at the earliest actually-swapped activity's
    # date - a stream re-designation can only alter the ledger from there onward.
    earliest_swapped = None
    for i, activity in enumerate(mismatched):
        replacement = (
            ActivityStream.objects.filter(activity_id=activity.id, field="power", source__source_kind=preference)
            .order_by("-source__created_at")
            .first()
        )
        if replacement is not None:
            _swap_primary_stream(activity.id, "power", replacement.source_id)
            recompute_activity_level_only(activity.id)
            swapped += 1
            swapped_date = activity.start_date.date()
            earliest_swapped = swapped_date if earliest_swapped is None else min(earliest_swapped, swapped_date)
        else:
            # This run's file never had the other candidate at all (e.g. a native-only Garmin
            # file, imported back when the athlete's preference happened to be "native" and
            # never re-uploaded since) - nothing stored to re-designate to, not an error.
            skipped += 1
        on_progress(i + 1)

    if swapped > 0:
        recompute_after_duplicate_change(athlete, from_date=earliest_swapped)
    return swapped, skipped


@shared_task(bind=True, max_retries=0)  # type: ignore[untyped-decorator]
def run_running_power_source_recompute(self: Any, job_id: str) -> None:
    job = RunningPowerSourceRecomputeJob.objects.select_related("athlete").get(pk=job_id)
    job.status = "processing"
    job.save(update_fields=["status"])

    def on_total(total: int) -> None:
        RunningPowerSourceRecomputeJob.objects.filter(pk=job.id).update(total_items=total)

    def on_progress(processed: int) -> None:
        RunningPowerSourceRecomputeJob.objects.filter(pk=job.id).update(processed_items=processed)

    try:
        swapped, skipped = recompute_running_power_source(job.athlete, on_total, on_progress)
    except Exception as exc:
        job.status = "failed"
        job.error_message = str(exc)[:500]
        job.completed_at = timezone.now()
        job.save(update_fields=["status", "error_message", "completed_at"])
        return

    job.status = "ready"
    job.swapped_items = swapped
    job.skipped_items = skipped
    job.completed_at = timezone.now()
    job.save(update_fields=["status", "swapped_items", "skipped_items", "completed_at"])
