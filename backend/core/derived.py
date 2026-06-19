from datetime import timedelta

from django.db.models import Sum
from django.utils import timezone

CTL_DAYS = 42
ATL_DAYS = 7
DEFAULT_FITNESS_WINDOW_DAYS = 84
COMPLIANCE_WINDOW_DAYS = 28


def _daily_tss(athlete_id, start_date, end_date):
    from activities.models import Activity

    rows = (
        Activity.objects.filter(athlete_id=athlete_id, start_date__date__range=(start_date, end_date))
        .values("start_date__date")
        .annotate(total_tss=Sum("tss"))
    )
    return {row["start_date__date"]: row["total_tss"] or 0 for row in rows}


def compute_fitness_series(athlete_id, from_date, to_date):
    """Daily CTL (42-day EWMA)/ATL (7-day EWMA)/TSB (CTL-ATL) of TSS.

    Walks day-by-day from the athlete's first-ever activity (so CTL/ATL start
    from a true 0/0 baseline rather than an arbitrary seed) through to_date,
    returning only the days in [from_date, to_date].
    """
    from activities.models import Activity

    first_activity_date = (
        Activity.objects.filter(athlete_id=athlete_id)
        .order_by("start_date")
        .values_list("start_date", flat=True)
        .first()
    )
    start = min(first_activity_date.date(), from_date) if first_activity_date else from_date
    if start > to_date:
        return []

    tss_by_day = _daily_tss(athlete_id, start, to_date)

    ctl = 0.0
    atl = 0.0
    series = []
    day = start
    while day <= to_date:
        tss = tss_by_day.get(day, 0)
        ctl += (tss - ctl) / CTL_DAYS
        atl += (tss - atl) / ATL_DAYS
        if day >= from_date:
            series.append({"date": day, "ctl": round(ctl, 1), "atl": round(atl, 1), "tsb": round(ctl - atl, 1)})
        day += timedelta(days=1)
    return series


def compute_ctl(athlete_id, as_of=None):
    as_of = as_of or timezone.now().date()
    series = compute_fitness_series(athlete_id, as_of, as_of)
    return series[0]["ctl"] if series else 0


def compute_atl(athlete_id, as_of=None):
    as_of = as_of or timezone.now().date()
    series = compute_fitness_series(athlete_id, as_of, as_of)
    return series[0]["atl"] if series else 0


def compute_tsb(athlete_id, as_of=None):
    as_of = as_of or timezone.now().date()
    series = compute_fitness_series(athlete_id, as_of, as_of)
    return series[0]["tsb"] if series else 0


def compute_compliance(athlete_id, as_of=None, window_days=COMPLIANCE_WINDOW_DAYS):
    """Share of scheduled workouts completed as planned, last `window_days` days."""
    from scheduling.models import ScheduledWorkout

    as_of = as_of or timezone.now().date()
    start = as_of - timedelta(days=window_days)
    qs = ScheduledWorkout.objects.filter(athlete_id=athlete_id, date__gte=start, date__lte=as_of)
    total = qs.count()
    if total == 0:
        return 0.0
    completed = qs.filter(status="completed").count()
    return round(completed / total, 2)


def compute_flags(athlete_id, as_of=None, window_days=COMPLIANCE_WINDOW_DAYS):
    """Count of attention-worthy issues on a coached athlete's roster card.

    v1: scheduled workouts that are still "planned" after their date has
    passed (no Beat job flips them to "missed", so we treat overdue-planned
    as the signal rather than mutating state on a read).
    """
    from scheduling.models import ScheduledWorkout

    as_of = as_of or timezone.now().date()
    start = as_of - timedelta(days=window_days)
    return ScheduledWorkout.objects.filter(
        athlete_id=athlete_id, status="planned", date__gte=start, date__lt=as_of
    ).count()
