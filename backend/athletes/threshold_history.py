"""Rolling-window threshold determination: FTP, critical running power, and threshold pace are
each derived as the best qualifying effort within a trailing window (User.threshold_window_days),
not a one-way ratchet - as an old best effort ages out of the window, a lower value takes over
automatically. See the plan this implements: threshold values can go down as well as up.

Pure/read-only - nothing here writes to the database. Callers (the ingest hook, the manual-refresh
endpoint, the bulk rebuild endpoint) are responsible for persisting the results.
"""

from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass
from datetime import date, timedelta

from accounts.models import User
from activities.models import Activity

FIELD_SPORT = {
    "ftp": "bike",
    "critical_run_power": "run",
    "threshold_pace": "run",
}

# Pace is seconds/km - a *lower* value is the improvement, the opposite of every other field.
LOWER_IS_BETTER = {"threshold_pace"}

# Bike FTP is conventionally estimated as 95% of best 20-minute power (the "20-minute test").
FTP_TEST_MULTIPLIER = 0.95
FTP_TEST_WINDOW_SECONDS = 1200
# Running threshold (both critical_run_power and threshold_pace) is conventionally estimated
# directly from a sustained ~1-hour effort - no multiplier, unlike bike's 20-minute test.
RUNNING_THRESHOLD_WINDOW_SECONDS = 3600


# A local copy, not a cross-app import of uploads/processing.py's private helpers - matches this
# codebase's existing convention of small local duplicates over reaching into another app's
# implementation detail (see e.g. ImportReader/ExportWriter's own Progress classes).
def _sliding_window_best_avg(values: Sequence[float], window: int) -> float | None:
    n = len(values)
    if window > n or window <= 0:
        return None
    window_sum = sum(values[:window])
    best = window_sum / window
    for i in range(window, n):
        window_sum += values[i] - values[i - window]
        avg = window_sum / window
        if avg > best:
            best = avg
    return best


def _best_pace_seconds_per_km_over_duration(
    t_series: Sequence[int], distance_km_series: Sequence[float | None], target_seconds: int
) -> float | None:
    cumulative: list[float] = []
    last = 0.0
    for d in distance_km_series:
        if d is not None:
            last = d
        cumulative.append(last)

    n = len(cumulative)
    best: float | None = None
    left = 0
    right = 0
    while left < n:
        if right < left:
            right = left
        while right < n and t_series[right] - t_series[left] < target_seconds:
            right += 1
        if right >= n:
            break
        duration = t_series[right] - t_series[left]
        actual_distance = cumulative[right] - cumulative[left]
        if duration > 0 and actual_distance > 0:
            pace = duration / actual_distance
            if best is None or pace < best:
                best = pace
        left += 1
    return best


def _mmss_to_seconds(value: str) -> int | None:
    if not value:
        return None
    parts = value.split(":")
    if len(parts) != 2:
        return None
    try:
        minutes, seconds = int(parts[0]), int(parts[1])
    except ValueError:
        return None
    return minutes * 60 + seconds


def _seconds_to_mmss(seconds: float) -> str:
    total = round(seconds)
    return f"{total // 60}:{total % 60:02d}"


def _implied_value(activity: Activity, field: str) -> float | None:
    """The value this one activity's own best effort implies for `field` - raw units (watts for
    ftp/critical_run_power, seconds/km for threshold_pace), or None if the activity has no
    qualifying effort (e.g. too short for the field's window)."""
    records = list(activity.records.order_by("t").values_list("t", "power", "distance_km"))
    if not records:
        return None
    t_series = [r[0] for r in records]
    power_series = [r[1] if r[1] is not None else 0 for r in records]
    distance_km_series = [r[2] for r in records]

    if field == "ftp":
        best_20min = _sliding_window_best_avg(power_series, FTP_TEST_WINDOW_SECONDS)
        return round(FTP_TEST_MULTIPLIER * best_20min) if best_20min is not None else None
    if field == "critical_run_power":
        best_60min = _sliding_window_best_avg(power_series, RUNNING_THRESHOLD_WINDOW_SECONDS)
        return round(best_60min) if best_60min is not None else None
    if field == "threshold_pace":
        return _best_pace_seconds_per_km_over_duration(t_series, distance_km_series, RUNNING_THRESHOLD_WINDOW_SECONDS)
    raise ValueError(f"Unknown field: {field}")


def _is_better(field: str, a: float, b: float) -> bool:
    """Whether candidate value `a` beats existing value `b` for this field."""
    return a < b if field in LOWER_IS_BETTER else a > b


def _within_sanity_band(field: str, candidate_value: float, reference_value: float | None, sanity_pct: int) -> bool:
    """False if candidate_value is an implausible outlier relative to reference_value (e.g.
    corrupt power-meter data) - always true when there's no reference yet, since a first-ever
    value has nothing to sanity-check against."""
    if reference_value is None or reference_value == 0:
        return True
    deviation = abs(candidate_value - reference_value) / reference_value
    return deviation <= sanity_pct / 100


@dataclass(frozen=True)
class Candidate:
    activity_id: str
    date: date
    implied_value: float


@dataclass(frozen=True)
class ThresholdHistoryEntry:
    field: str
    value: float
    activity_id: str
    effective_from: date


def _athlete_reference_value(athlete: User, field: str) -> float | None:
    raw = getattr(athlete, field)
    return _mmss_to_seconds(raw) if field == "threshold_pace" else raw


def current_window_value(athlete: User, field: str, as_of: date | None = None) -> Candidate | None:
    """The best qualifying candidate among the athlete's activities in the trailing window ending
    `as_of` (default today) for `field` - cheap, bounded to ~window_days worth of activities.
    Used by both the ingest hook and the manual-refresh endpoint. Sanity-filters each candidate
    against the athlete's current profile value (not a moving reference - this is a single
    snapshot-in-time scan, not a chronological replay; see replay_full_history for that)."""
    as_of = as_of or date.today()
    window_start = as_of - timedelta(days=athlete.threshold_window_days)
    reference_value = _athlete_reference_value(athlete, field)

    activities = Activity.objects.filter(
        athlete=athlete,
        sport=FIELD_SPORT[field],
        start_date__date__gte=window_start,
        start_date__date__lte=as_of,
    ).order_by("start_date")

    best: Candidate | None = None
    for activity in activities:
        implied = _implied_value(activity, field)
        if implied is None:
            continue
        if not _within_sanity_band(field, implied, reference_value, athlete.threshold_sanity_pct):
            continue
        if best is None or _is_better(field, implied, best.implied_value):
            best = Candidate(activity_id=activity.id, date=activity.start_date.date(), implied_value=implied)
    return best


def replay_full_history(athlete: User, field: str) -> list[ThresholdHistoryEntry]:
    """Rebuilds the full history ledger for one field by replaying the athlete's activities
    oldest-first, applying the same windowed-best-with-sanity-filter rule as current_window_value
    but incrementally over time - a date-windowed sliding-window-maximum problem (not count-
    windowed): `window` is a monotonic list of not-yet-expired, not-yet-dominated candidates, kept
    ordered so the front is always the current best (mirrors the classic sliding-window-maximum
    deque trick - candidates it dominates can never become the max again while it's still in the
    window, so they're safe to drop the moment a stronger, more recent one arrives).

    Used only by the bulk "recompute history from oldest" tool - current_window_value is what
    ingest/refresh use day to day.
    """
    activities = list(Activity.objects.filter(athlete=athlete, sport=FIELD_SPORT[field]).order_by("start_date"))

    entries: list[ThresholdHistoryEntry] = []
    window: list[Candidate] = []
    current_value: float | None = None

    for activity in activities:
        activity_date = activity.start_date.date()
        cutoff = activity_date - timedelta(days=athlete.threshold_window_days)
        window = [c for c in window if c.date > cutoff]

        implied = _implied_value(activity, field)
        if implied is not None and _within_sanity_band(field, implied, current_value, athlete.threshold_sanity_pct):
            candidate = Candidate(activity_id=activity.id, date=activity_date, implied_value=implied)
            window = [c for c in window if not _is_better(field, implied, c.implied_value)]
            window.append(candidate)

        best = window[0] if window else None
        new_value = best.implied_value if best else None
        if new_value != current_value:
            current_value = new_value
            if best is not None:
                entries.append(
                    ThresholdHistoryEntry(
                        field=field, value=new_value, activity_id=best.activity_id, effective_from=best.date
                    )
                )

    return entries
