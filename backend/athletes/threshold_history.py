"""Rolling-window threshold determination: FTP, critical running power, and threshold pace are
each derived as the best qualifying effort within a trailing window (User.threshold_window_days),
not a one-way ratchet - as an old best effort ages out of the window, a lower value takes over
automatically. See the plan this implements: threshold values can go down as well as up.

Pure/read-only - nothing here writes to the database. Callers (the ingest hook, the manual-refresh
endpoint, the bulk rebuild endpoint) are responsible for persisting the results.
"""

from __future__ import annotations

from collections.abc import Iterator, Sequence
from dataclasses import dataclass
from datetime import date, timedelta

from accounts.models import User
from activities.models import Activity

from .models import ThresholdHistory

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
# directly from a sustained ~1-hour effort - no multiplier, unlike bike's 20-minute test. Also
# reused for FTP under ftp_calculation_method="sixty_min_direct" - same window, same "direct, no
# multiplier" reasoning, just applied to bike power instead of running.
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
        if activity.athlete.ftp_calculation_method == "sixty_min_direct":
            best_60min = _sliding_window_best_avg(power_series, RUNNING_THRESHOLD_WINDOW_SECONDS)
            return round(best_60min) if best_60min is not None else None
        best_20min = _sliding_window_best_avg(power_series, FTP_TEST_WINDOW_SECONDS)
        return round(FTP_TEST_MULTIPLIER * best_20min) if best_20min is not None else None
    if field == "critical_run_power":
        # Excluded (source doesn't match the athlete's current preference), or every sample is
        # genuinely absent (e.g. "native" preferred but this file has no native power meter at
        # all) - either way, not a qualifying candidate. _sliding_window_best_avg treats a
        # missing sample as 0W, not "no data" (power_series above already substituted 0 for
        # every None), so an all-absent series must be caught here rather than left to fall
        # through to it.
        if not activity.matches_running_power_preference(activity.athlete) or not any(
            r[1] is not None for r in records
        ):
            return None
        best_60min = _sliding_window_best_avg(power_series, RUNNING_THRESHOLD_WINDOW_SECONDS)
        return round(best_60min) if best_60min is not None else None
    if field == "threshold_pace":
        best_pace = _best_pace_seconds_per_km_over_duration(
            t_series, distance_km_series, RUNNING_THRESHOLD_WINDOW_SECONDS
        )
        # Rounded to whole seconds like ftp/critical_run_power above - value_pace is stored (and
        # re-parsed via _mmss_to_seconds for _recompute_and_record's dead-row comparison) as
        # whole-second "M:SS", so a raw sub-second float here could never compare equal to the
        # already-rounded stored value: every re-ingest inserted a spurious duplicate row for the
        # same still-current pace instead of recognizing it as unchanged.
        return round(best_pace) if best_pace is not None else None
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
    current_from: date


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


def _replay_full_history_into(
    athlete: User, field: str, entries_out: list[ThresholdHistoryEntry]
) -> Iterator[tuple[int, int]]:
    """The actual replay loop, shared by replay_full_history (silently exhausted) and
    rebuild_history_stream (surfaced as SSE progress) - appends to `entries_out` in place
    (rather than returning a list directly) so a generator can also yield (current, total)
    progress after each activity without needing a separate non-generator code path to stay in
    sync. A date-windowed sliding-window-maximum problem (not count-windowed): `window` is a
    monotonic list of not-yet-expired, not-yet-dominated candidates, kept ordered so the front is
    always the current best (mirrors the classic sliding-window-maximum deque trick - candidates
    it dominates can never become the max again while it's still in the window, so they're safe
    to drop the moment a stronger, more recent one arrives)."""
    activities = list(Activity.objects.filter(athlete=athlete, sport=FIELD_SPORT[field]).order_by("start_date"))
    total = len(activities)

    window: list[Candidate] = []
    current_value: float | None = None

    for i, activity in enumerate(activities):
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
                # current_from is *this* iteration's activity_date - the date whose recompute
                # pass discovered best as the new window winner - not best.date (effective_from),
                # which can be much earlier when best only wins now because a better, more
                # recent entry aged out from under it (see ThresholdHistoryEntry's docstring).
                entries_out.append(
                    ThresholdHistoryEntry(
                        field=field,
                        value=new_value,
                        activity_id=best.activity_id,
                        effective_from=best.date,
                        current_from=activity_date,
                    )
                )
        yield i + 1, total


def replay_full_history(athlete: User, field: str) -> list[ThresholdHistoryEntry]:
    """Rebuilds the full history ledger for one field by replaying the athlete's activities
    oldest-first, applying the same windowed-best-with-sanity-filter rule as current_window_value
    but incrementally over time - see _replay_full_history_into. Used only by the bulk "recompute
    history from oldest" tool - current_window_value is what ingest/refresh use day to day.
    """
    entries: list[ThresholdHistoryEntry] = []
    for _ in _replay_full_history_into(athlete, field, entries):
        pass
    return entries


def _latest_entry(athlete: User, field: str) -> ThresholdHistory | None:
    return ThresholdHistory.objects.filter(athlete=athlete, field=field).order_by("-effective_from").first()


def _record_candidate(athlete: User, field: str, candidate: Candidate, current_from: date) -> None:
    """Writes a new ThresholdHistory row for `candidate` and updates the athlete's cached
    profile value to match. Caller is responsible for confirming this candidate actually
    represents a change worth recording. `current_from` is the date this recompute pass ran as
    of - see ThresholdHistoryEntry's docstring for why that can differ from candidate.date."""
    if field == "threshold_pace":
        value_numeric, value_pace = None, _seconds_to_mmss(candidate.implied_value)
    else:
        value_numeric, value_pace = round(candidate.implied_value), ""
    ThresholdHistory.objects.create(
        athlete=athlete,
        field=field,
        value_numeric=value_numeric,
        value_pace=value_pace,
        source_activity_id=candidate.activity_id,
        effective_from=candidate.date,
        current_from=current_from,
    )
    setattr(athlete, field, value_pace if field == "threshold_pace" else value_numeric)
    athlete.save(update_fields=[field])


def _recompute_and_record(athlete: User, field: str, as_of: date | None = None) -> bool:
    """Runs current_window_value and records it as a new ThresholdHistory entry if it differs
    from the latest one on file. Returns whether anything actually changed. Shared by
    recompute_for_activity (activity-triggered, as_of that activity's own date) and
    refresh_field (athlete-triggered, as_of today)."""
    effective_as_of = as_of or date.today()
    candidate = current_window_value(athlete, field, as_of=effective_as_of)
    if candidate is None:
        return False
    latest = _latest_entry(athlete, field)
    # "Current" (is_stale/the dashboard summary) is whichever row has the latest effective_from,
    # not whichever was inserted most recently - so a candidate dated *before* the existing
    # current entry could never actually become current, however different its value. Recording
    # it anyway is worse than a no-op: it's a dead row that silently re-inserts itself (with a
    # new id) every time the ingest hook or a manual refresh re-evaluates the same activity,
    # since its value keeps comparing as "different" from the still-unbeaten current entry. Seen
    # for real (Java backend, same algorithm): a stale manually-entered FTP permanently outranked
    # a genuine ride-based candidate dated three weeks earlier, and every re-trigger added
    # another identical dead row instead of just doing nothing.
    if latest is not None and candidate.date < latest.effective_from:
        return False
    latest_value = None
    if latest is not None:
        latest_value = _mmss_to_seconds(latest.value_pace) if field == "threshold_pace" else latest.value_numeric
    if latest_value == candidate.implied_value:
        return False
    _record_candidate(athlete, field, candidate, current_from=effective_as_of)
    return True


def recompute_for_activity(activity: Activity) -> None:
    """Checks whether `activity`'s own effort changes the athlete's current window value for
    each field relevant to its sport (bike -> ftp; run -> critical_run_power and
    threshold_pace), writing a new ThresholdHistory entry (and updating the athlete's cached
    profile value) if so. Called at ingest/import time - see
    uploads/processing.py::_ingest_activity and dataexport/import_reader.py. A no-op for every
    other sport, same as detection always was.
    """
    athlete = activity.athlete
    for field, sport in FIELD_SPORT.items():
        if sport == activity.sport:
            _recompute_and_record(athlete, field, as_of=activity.start_date.date())


def record_manual_value(athlete: User, field: str, raw_value: int | str, as_of: date | None = None) -> bool:
    """The athlete directly declared this value via their profile (PATCH /v1/athletes/<id>) -
    trusted unconditionally (no sanity-band check, no window search: an explicit choice, not a
    computed candidate), effective from today. Behaves exactly like any other ledger entry from
    here on: ages out after threshold_window_days like any other, and can be superseded by a
    later qualifying activity or another manual edit. A no-op if the submitted value matches
    what's already current (e.g. re-saving the profile form without touching this field - the
    frontend always resubmits every field). Doesn't touch athlete.<field> itself - the caller
    (AthleteUpdateSerializer.save()) already wrote that as part of the same request."""
    implied_value = _mmss_to_seconds(raw_value) if field == "threshold_pace" else raw_value
    if not implied_value:
        return False
    latest = _latest_entry(athlete, field)
    latest_value = None
    if latest is not None:
        latest_value = _mmss_to_seconds(latest.value_pace) if field == "threshold_pace" else latest.value_numeric
    if latest_value == implied_value:
        return False
    value_numeric, value_pace = (None, raw_value) if field == "threshold_pace" else (raw_value, "")
    effective_as_of = as_of or date.today()
    ThresholdHistory.objects.create(
        athlete=athlete,
        field=field,
        value_numeric=value_numeric,
        value_pace=value_pace,
        source_activity=None,
        effective_from=effective_as_of,
        current_from=effective_as_of,
    )
    return True


def refresh_field(athlete: User, field: str) -> bool:
    """Manual on-demand recompute (the dashboard's "this value is stale - refresh now" action) -
    the same cheap current-window computation as the ingest hook, just athlete-triggered rather
    than tied to a specific new activity. Returns whether the value actually changed."""
    return _recompute_and_record(athlete, field)


def is_stale(athlete: User, field: str, as_of: date | None = None) -> bool:
    """Whether the current entry's source activity has aged out of the trailing window - a
    plain date comparison, not a recompute, so it's cheap enough to call on every read (e.g. the
    dashboard). True with no entry at all (nothing to be current)."""
    as_of = as_of or date.today()
    latest = _latest_entry(athlete, field)
    if latest is None:
        return True
    return (as_of - latest.effective_from).days > athlete.threshold_window_days


def rebuild_history_stream(athlete: User, field: str) -> Iterator[tuple[int, int]]:
    """The bulk 'recompute history from oldest' tool - replaces the entire ledger for one field
    with a fresh replay, then syncs the athlete's cached profile value to the result (or clears
    it if the athlete has no qualifying activities for this field at all). Yields (current,
    total) progress after each activity processed, for the SSE bulk-rebuild endpoint."""
    ThresholdHistory.objects.filter(athlete=athlete, field=field).delete()
    entries: list[ThresholdHistoryEntry] = []
    yield from _replay_full_history_into(athlete, field, entries)

    ThresholdHistory.objects.bulk_create(
        ThresholdHistory(
            athlete=athlete,
            field=field,
            value_numeric=None if field == "threshold_pace" else round(entry.value),
            value_pace=_seconds_to_mmss(entry.value) if field == "threshold_pace" else "",
            source_activity_id=entry.activity_id,
            effective_from=entry.effective_from,
            current_from=entry.current_from,
        )
        for entry in entries
    )
    current = entries[-1].value if entries else None
    if field == "threshold_pace":
        setattr(athlete, field, _seconds_to_mmss(current) if current is not None else "")
    else:
        setattr(athlete, field, round(current) if current is not None else None)
    athlete.save(update_fields=[field])
