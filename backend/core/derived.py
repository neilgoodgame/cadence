from datetime import date, timedelta
from typing import Any

from django.db.models import Sum
from django.utils import timezone

CTL_DAYS = 42
ATL_DAYS = 7
DEFAULT_FITNESS_WINDOW_DAYS = 84
COMPLIANCE_WINDOW_DAYS = 28


def _daily_tss(
    athlete_id: str, start_date: date, end_date: date, sport: str | None = None, include_duplicates: bool = False
) -> dict[date, int]:
    from activities.models import Activity

    qs = (
        # Multisport children are excluded: the parent already carries the sum of its
        # legs' TSS, so counting both would double the training load.
        Activity.objects.filter(
            athlete_id=athlete_id,
            start_date__date__range=(start_date, end_date),
            parent_activity__isnull=True,
        )
    )
    if not include_duplicates:
        # Duplicate recordings are excluded for the same reason as multisport children: only
        # the primary counts. `include_duplicates` (issue #498) exists only for the MCP
        # get_fitness_trend tool's ad-hoc "what if I counted this duplicate" inspection - every
        # other caller leaves it at the default.
        qs = qs.filter(primary_activity__isnull=True)
    if sport is not None:
        qs = qs.filter(sport=sport)
    rows = qs.values("start_date__date").annotate(total_tss=Sum("tss"))
    return {row["start_date__date"]: row["total_tss"] or 0 for row in rows}


def _first_activity_date(athlete_id: str) -> date | None:
    from activities.models import Activity

    first_activity_date = (
        Activity.objects.filter(athlete_id=athlete_id)
        .order_by("start_date")
        .values_list("start_date", flat=True)
        .first()
    )
    return first_activity_date.date() if first_activity_date else None


def _clamped_start(athlete_id: str, from_date: date, to_date: date) -> date | None:
    """The baseline start date both compute_fitness_series and compute_above_z2_fitness_series
    walk their EWMA from - the athlete's first-ever activity (of any sport - a sport/intensity
    filter narrows which days count, not when the EWMA starts warming up from) if earlier than
    from_date, else from_date itself. None (not from_date) when that start is already after
    to_date, so both callers can return an empty series with one shared check rather than two
    copies of the same comparison."""
    first = _first_activity_date(athlete_id)
    start = min(first, from_date) if first else from_date
    return None if start > to_date else start


def _ewma_series(daily_values: dict[date, float], start: date, from_date: date, to_date: date) -> list[dict[str, Any]]:
    """The CTL (42-day EWMA)/ATL (7-day EWMA)/TSB (CTL-ATL) walk, generalized to any per-day
    load value (issue #487) - originally hardwired to `_daily_tss`'s TSS, now a plain
    `dict[date, float]` any caller can build. Walks day-by-day from `start` (so CTL/ATL start
    from a true 0/0 baseline rather than an arbitrary seed) through `to_date`, returning only the
    days in `[from_date, to_date]`."""
    ctl = 0.0
    atl = 0.0
    series = []
    day = start
    while day <= to_date:
        value = daily_values.get(day, 0)
        ctl += (value - ctl) / CTL_DAYS
        atl += (value - atl) / ATL_DAYS
        if day >= from_date:
            series.append({"date": day, "ctl": round(ctl, 1), "atl": round(atl, 1), "tsb": round(ctl - atl, 1)})
        day += timedelta(days=1)
    return series


def compute_fitness_series(
    athlete_id: str, from_date: date, to_date: date, sport: str | None = None, include_duplicates: bool = False
) -> list[dict[str, Any]]:
    """Daily CTL/ATL/TSB of TSS. `sport`, if given, restricts the series to just that sport's
    activities (e.g. a bike-only fitness trend) - every other caller's combined-sport series is
    unaffected. `include_duplicates` is the same ad-hoc override _daily_tss documents. See
    _ewma_series for the day-walk itself; the baseline start date is always the athlete's
    first-ever activity regardless of `sport` - a sport filter narrows which TSS counts, not
    when the EWMA starts warming up from."""
    start = _clamped_start(athlete_id, from_date, to_date)
    if start is None:
        return []

    tss_by_day = _daily_tss(athlete_id, start, to_date, sport, include_duplicates)
    return _ewma_series(tss_by_day, start, from_date, to_date)


def _daily_above_z2_load(
    athlete, start_date: date, end_date: date, include_duplicates: bool = False
) -> dict[date, float]:
    """Issue #487: every bike activity's above-Z2 training load (decay_load.
    above_z2_load_for_activity, field="ftp" - the load term's own bike-only scope), summed per
    day. Same multisport-child/duplicate exclusion _daily_tss uses, restricted to sport="bike"
    since there's no pace-based above-Z2 histogram for runs.

    Unlike _daily_tss's single aggregate SQL query, this is one query per activity (histogram
    lazy-build + the ledger lookup for that activity's own in-effect reference, inside
    above_z2_load_for_activity) - the same O(activities) shape load_by_day_for_athlete's own
    histogram lookup already has, now with one more per-activity lookup on top for the
    per-activity (not single-anchor) reference resolution the plan for this feature calls for.
    Acceptable for a calibration tool queried occasionally over a bounded window, not a
    high-frequency dashboard path - revisit if that ever changes."""
    from activities.models import Activity
    from athletes.decay_load import above_z2_load_for_activity
    from athletes.threshold_history import _excluded_activity_ids

    qs = Activity.objects.filter(
        athlete=athlete, sport="bike", start_date__date__range=(start_date, end_date), parent_activity__isnull=True
    )
    if not include_duplicates:
        qs = qs.filter(primary_activity__isnull=True)

    excluded_ids = _excluded_activity_ids(athlete, "ftp")  # loaded once, not per activity
    daily_loads: dict[date, float] = {}
    for activity in qs:
        load = above_z2_load_for_activity(activity, athlete, "ftp", excluded_ids=excluded_ids) or 0.0
        day = activity.start_date.date()
        daily_loads[day] = daily_loads.get(day, 0.0) + load
    return daily_loads


def compute_above_z2_fitness_series(
    athlete_id: str, from_date: date, to_date: date, include_duplicates: bool = False
) -> list[dict[str, Any]]:
    """Issue #487: as compute_fitness_series, but CTL/ATL/TSB built from above-Z2 training load
    (_daily_above_z2_load) instead of raw TSS - a bike-only series (there's no pace-based above-Z2
    histogram for runs) for comparing against the regular, TSS-based trend when calibrating the
    threshold-decay load term. Same baseline-start rule as compute_fitness_series: the athlete's
    first-ever activity of any sport, not just bike."""
    from accounts.models import User

    start = _clamped_start(athlete_id, from_date, to_date)
    if start is None:
        return []

    athlete = User.objects.get(pk=athlete_id)
    daily_loads = _daily_above_z2_load(athlete, start, to_date, include_duplicates)
    return _ewma_series(daily_loads, start, from_date, to_date)


def compute_ctl(athlete_id: str, as_of: date | None = None) -> float:
    as_of = as_of or timezone.now().date()
    series = compute_fitness_series(athlete_id, as_of, as_of)
    return series[0]["ctl"] if series else 0


def compute_atl(athlete_id: str, as_of: date | None = None) -> float:
    as_of = as_of or timezone.now().date()
    series = compute_fitness_series(athlete_id, as_of, as_of)
    return series[0]["atl"] if series else 0


def compute_tsb(athlete_id: str, as_of: date | None = None) -> float:
    as_of = as_of or timezone.now().date()
    series = compute_fitness_series(athlete_id, as_of, as_of)
    return series[0]["tsb"] if series else 0


def compute_compliance(athlete_id: str, as_of: date | None = None, window_days: int = COMPLIANCE_WINDOW_DAYS) -> float:
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


def compute_flags(athlete_id: str, as_of: date | None = None, window_days: int = COMPLIANCE_WINDOW_DAYS) -> int:
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
