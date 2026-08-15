from celery import shared_task
from django.db import connection
from django.utils import timezone


def _month_add(year: int, month: int, months: int) -> tuple[int, int]:
    total = year * 12 + (month - 1) + months
    return total // 12, total % 12 + 1


@shared_task
def ensure_record_partitions() -> None:
    """Idempotently creates any missing activities_record partitions for the next 6
    months, so new activity data always has a real (non-DEFAULT) partition to land in
    well ahead of time. Same monthly boundaries/naming as migration
    0003_record_native_partitioning's initial partition scheme - this just keeps
    rolling the forward edge ahead over time. Safe to run repeatedly (CREATE TABLE IF
    NOT EXISTS) and safe to run late (that migration's DEFAULT partition catches
    anything this hasn't created yet in the meantime)."""
    today = timezone.now().date()
    with connection.cursor() as cursor:
        for offset in range(7):  # this month through 6 months ahead
            y, m = _month_add(today.year, today.month, offset)
            ny, nm = _month_add(today.year, today.month, offset + 1)
            name = f"activities_record_p{y:04d}{m:02d}"
            cursor.execute(
                f"CREATE TABLE IF NOT EXISTS {name} PARTITION OF activities_record "
                f"FOR VALUES FROM ('{y:04d}-{m:02d}-01T00:00:00+00') "
                f"TO ('{ny:04d}-{nm:02d}-01T00:00:00+00');"
            )
