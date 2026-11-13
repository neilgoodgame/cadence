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
from dataclasses import field as dataclass_field
from datetime import date, timedelta

from django.db import transaction
from rest_framework.exceptions import ValidationError

from accounts.models import User
from activities.models import Activity, Record
from races.models import Race

from .models import AcceptedThresholdCandidate, ThresholdHistory

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


@dataclass(frozen=True)
class ImpliedValueDetail:
    window: str
    raw_value: float
    value: float


def _implied_value_detail(
    activity: Activity, field: str, records: list[tuple[int, int | None, float | None]] | None = None
) -> ImpliedValueDetail | None:
    """Like _implied_value, but also returns the window label and the raw pre-transform value
    (e.g. the raw 20-min power before FTP's x0.95 multiplier) - None for the no-qualifying-
    effort case. Used directly by the Threshold suggestions feature's "implies" copy for a
    rejected candidate (threshold_suggestions.py); _implied_value below is a thin wrapper over
    this for every other, much more numerous caller that only ever wanted the final value.

    `records` lets a caller scanning many activities in one call (rejected_candidates,
    candidate_statuses, current_window_value) pass in a batch-fetched (t, power, distance_km)
    list instead of this method querying per activity - see _batched_records_for's own
    docstring for why a per-activity query doesn't scale for those callers (issue #500).
    Omitted (None, not just empty), this activity's own records are fetched here directly - for
    a caller considering one activity at a time where that's cheap enough on its own
    (_replay_full_history_into's whole-history replay, upcoming_drop's 14-day recent-match
    check)."""
    if records is None:
        records = list(activity.records.order_by("t").values_list("t", "power", "distance_km"))
    if not records:
        return None
    t_series = [r[0] for r in records]
    power_series = [r[1] if r[1] is not None else 0 for r in records]
    distance_km_series = [r[2] for r in records]

    if field == "ftp":
        # Issue #499/#503 (phase 1): excluded if this activity's attached-primary power source
        # disagrees with the athlete's *current* bike_power_source preference - same
        # re-check-on-every-recompute pattern as critical_run_power below, but "any" (the
        # default) always matches, and an unset bike_power_source (never had a second source
        # attached - the common case) also always matches.
        if not activity.matches_bike_power_preference(activity.athlete):
            return None
        if activity.athlete.ftp_calculation_method == "sixty_min_direct":
            best_60min = _sliding_window_best_avg(power_series, RUNNING_THRESHOLD_WINDOW_SECONDS)
            return ImpliedValueDetail("60min", best_60min, round(best_60min)) if best_60min is not None else None
        best_20min = _sliding_window_best_avg(power_series, FTP_TEST_WINDOW_SECONDS)
        if best_20min is None:
            return None
        return ImpliedValueDetail("20min", best_20min, round(FTP_TEST_MULTIPLIER * best_20min))
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
        return ImpliedValueDetail("60min", best_60min, round(best_60min)) if best_60min is not None else None
    if field == "threshold_pace":
        best_pace = _best_pace_seconds_per_km_over_duration(
            t_series, distance_km_series, RUNNING_THRESHOLD_WINDOW_SECONDS
        )
        if best_pace is None:
            return None
        # Rounded to whole seconds like ftp/critical_run_power above - value_pace is stored (and
        # re-parsed via _mmss_to_seconds for _recompute_and_record's dead-row comparison) as
        # whole-second "M:SS", so a raw sub-second float here could never compare equal to the
        # already-rounded stored value: every re-ingest inserted a spurious duplicate row for the
        # same still-current pace instead of recognizing it as unchanged.
        return ImpliedValueDetail("60min", best_pace, round(best_pace))
    raise ValueError(f"Unknown field: {field}")


def _implied_value(activity: Activity, field: str) -> float | None:
    """The value this one activity's own best effort implies for `field` - raw units (watts for
    ftp/critical_run_power, seconds/km for threshold_pace), or None if the activity has no
    qualifying effort (e.g. too short for the field's window)."""
    detail = _implied_value_detail(activity, field)
    return detail.value if detail is not None else None


# Chunk size for _batched_records_for below - one query per chunk instead of one per activity
# (issue #500), but still small enough that a chunk's worth of 1 Hz streams held in memory at
# once stays bounded, rather than fetching a whole multi-hundred-activity request's records in
# one shot.
_RECORD_BATCH_SIZE = 20


def _chunked(activities: Sequence[Activity], size: int = _RECORD_BATCH_SIZE) -> Iterator[Sequence[Activity]]:
    for i in range(0, len(activities), size):
        yield activities[i : i + size]


def _batched_records_for(chunk: Sequence[Activity]) -> dict[str, list[tuple[int, int | None, float | None]]]:
    """One query for every activity in `chunk`'s records, instead of one per activity (issue
    #500): record is natively range-partitioned on `ts` (see the Record model's own docstring),
    so a query that filters only by activity_id - no ts bound at all - can't be pruned to any
    partition and has to fan out across every one of them; a scan over many activities (a whole
    year of threshold-calibration candidates, for list_threshold_candidates) multiplies that fan-
    out cost once per activity. Scoping by `chunk`'s own start_date span lets Postgres prune to
    just the relevant few partitions instead - `chunk` should be one of _chunked's pieces, not a
    whole request's activities at once, both to keep that span tight and to bound how many
    activities' streams are held in memory at once (see _RECORD_BATCH_SIZE's own comment)."""
    if not chunk:
        return {}
    activity_ids = [a.id for a in chunk]
    # chunk is start_date-ordered (every caller below orders activities by start_date before
    # chunking), so the first/last give the span cheaply - 1 day/2 days of padding on either
    # edge comfortably covers any single activity's own duration.
    ts_start = chunk[0].start_date - timedelta(days=1)
    ts_end = chunk[-1].start_date + timedelta(days=2)
    rows = (
        Record.objects.filter(activity_id__in=activity_ids, ts__gte=ts_start, ts__lt=ts_end)
        .order_by("activity_id", "t")
        .values_list("activity_id", "t", "power", "distance_km")
    )
    batched: dict[str, list[tuple[int, int | None, float | None]]] = {}
    for activity_id, t, power, distance_km in rows:
        batched.setdefault(activity_id, []).append((t, power, distance_km))
    return batched


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
    activity_id: str | None
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


def _accepted_activity_ids(athlete: User, field: str) -> set[str]:
    """Activity ids the athlete has explicitly accepted as real despite failing the sanity band -
    see the Threshold suggestions feature's AcceptedThresholdCandidate. Bypasses the band in both
    current_window_value and _replay_full_history_into below, so an accepted candidate survives
    a full rebuild/replay instead of being rejected again against a moving reference."""
    return set(
        AcceptedThresholdCandidate.objects.filter(athlete=athlete, field=field).values_list("activity_id", flat=True)
    )


def _row_value(field: str, row: ThresholdHistory) -> float | None:
    """A ledger row's own value as a plain float, regardless of field (pace is stored as "M:SS",
    every other field as a plain number) - shared by _manual_anchor_candidate above and
    _replay_full_history_into's manual-row merge below."""
    return _mmss_to_seconds(row.value_pace) if field == "threshold_pace" else row.value_numeric


def _manual_anchor_candidate(athlete: User, field: str, window_start: date) -> Candidate | None:
    """A manual value lasts until it's beaten by a later qualifying activity or ages out of the
    window, same as any other ledger entry (issue #484) - current_window_value's activity-only
    scan can't see the ledger on its own, so a still-fresh manual row is injected here as a
    candidate with its own value, to compete on equal terms. A no-op for a measured anchor: its
    source activity is already in the activities scan below and wins there on its own."""
    latest = _latest_entry(athlete, field)
    if latest is None or latest.kind != "manual" or latest.effective_from < window_start:
        return None
    manual_value = _row_value(field, latest)
    if manual_value is None:
        return None
    return Candidate(activity_id=None, date=latest.effective_from, implied_value=manual_value)


def current_window_value(athlete: User, field: str, as_of: date | None = None) -> Candidate | None:
    """The best qualifying candidate among the athlete's activities in the trailing window ending
    `as_of` (default today) for `field` - cheap, bounded to ~window_days worth of activities.
    Used by both the ingest hook and the manual-refresh endpoint. Sanity-filters each candidate
    against the athlete's current profile value (not a moving reference - this is a single
    snapshot-in-time scan, not a chronological replay; see replay_full_history for that), except
    for an explicitly accepted candidate (see _accepted_activity_ids), which always qualifies.
    Also considers the latest manual entry, if still within the window, as a candidate in its own
    right (see _manual_anchor_candidate) - otherwise it's invisible here and gets replaced by the
    first later activity regardless of value, however low (issue #484)."""
    as_of = as_of or date.today()
    window_start = as_of - timedelta(days=athlete.threshold_window_days)
    reference_value = _athlete_reference_value(athlete, field)
    accepted_ids = _accepted_activity_ids(athlete, field)

    activities = list(
        Activity.objects.filter(
            athlete=athlete,
            sport=FIELD_SPORT[field],
            primary_activity__isnull=True,
            start_date__date__gte=window_start,
            start_date__date__lte=as_of,
        ).order_by("start_date")
    )

    best: Candidate | None = _manual_anchor_candidate(athlete, field, window_start)
    for chunk in _chunked(activities):
        batched_records = _batched_records_for(chunk)
        for activity in chunk:
            detail = _implied_value_detail(activity, field, batched_records.get(activity.id, []))
            implied = detail.value if detail is not None else None
            if implied is None:
                continue
            if activity.id not in accepted_ids and not _within_sanity_band(
                field, implied, reference_value, athlete.threshold_sanity_pct
            ):
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
    to drop the moment a stronger, more recent one arrives).

    Every still-current (not correction-only) manual row is merged into the same window at its
    own effective_from, exactly like _manual_anchor_candidate's single as-of snapshot but
    repeated at every point in the replay (issue #504) - rebuild_history_stream only deletes
    kind="measured" rows before calling this, so a manual row's own ledger entry is never
    rewritten here; this only makes sure a *measured* activity dated after it is correctly
    suppressed/revealed relative to it, the same #484 rule current_window_value already gets via
    _manual_anchor_candidate. A correction-only row is deliberately excluded from this merge - it
    never competed to be current, so it shouldn't start winning the window on replay either."""
    activities = list(
        Activity.objects.filter(athlete=athlete, sport=FIELD_SPORT[field], primary_activity__isnull=True).order_by(
            "start_date"
        )
    )
    total = len(activities)
    accepted_ids = _accepted_activity_ids(athlete, field)
    manual_rows = list(
        ThresholdHistory.objects.filter(athlete=athlete, field=field, kind="manual", is_correction_only=False).order_by(
            "effective_from"
        )
    )
    next_manual_idx = 0

    window: list[Candidate] = []
    current_value: float | None = None

    for i, activity in enumerate(activities):
        activity_date = activity.start_date.date()

        # Merge in every manual row whose effective_from has arrived as of this activity, before
        # this activity's own candidacy is decided - it has to compete against (and potentially
        # be suppressed by) a manual row dated on or before it, same ordering
        # current_window_value already gets for free from a single as-of snapshot.
        while next_manual_idx < len(manual_rows) and manual_rows[next_manual_idx].effective_from <= activity_date:
            manual_row = manual_rows[next_manual_idx]
            next_manual_idx += 1
            manual_value = _row_value(field, manual_row)
            if manual_value is not None:
                window = [c for c in window if not _is_better(field, manual_value, c.implied_value)]
                # activity_id None, same as _manual_anchor_candidate - this candidate's ledger
                # row already exists (it wasn't deleted), so no entry is written for it below.
                window.append(Candidate(activity_id=None, date=manual_row.effective_from, implied_value=manual_value))

        cutoff = activity_date - timedelta(days=athlete.threshold_window_days)
        window = [c for c in window if c.date > cutoff]

        implied = _implied_value(activity, field)
        qualifies = implied is not None and (
            activity.id in accepted_ids
            or _within_sanity_band(field, implied, current_value, athlete.threshold_sanity_pct)
        )
        if qualifies:
            candidate = Candidate(activity_id=activity.id, date=activity_date, implied_value=implied)
            window = [c for c in window if not _is_better(field, implied, c.implied_value)]
            window.append(candidate)

        best = window[0] if window else None
        new_value = best.implied_value if best else None
        if new_value != current_value:
            current_value = new_value
            # Only a measured winner (a real activity) needs a fresh entry - a manual winner's
            # own row already exists in the ledger untouched (see this function's own
            # docstring), so writing one here would duplicate it.
            if best is not None and best.activity_id is not None:
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


def replay_from(athlete: User, field: str, from_date: date) -> list[ThresholdHistoryEntry]:
    """As replay_full_history, but replays only from `from_date` onward - drains
    _replay_from_into silently. See that function for the actual replay/seeding logic."""
    entries: list[ThresholdHistoryEntry] = []
    for _ in _replay_from_into(athlete, field, from_date, entries):
        pass
    return entries


def _replay_from_into(
    athlete: User, field: str, from_date: date, entries_out: list[ThresholdHistoryEntry]
) -> Iterator[tuple[int, int]]:
    """As _replay_full_history_into, but replays only from `from_date` onward (issue #545) - a
    duplicate-status change on an activity dated d can only alter the ledger from d onward, so
    this re-derives just that tail instead of replaying the athlete's whole history. Appends
    exactly the entries replay_full_history would emit with current_from >= from_date; rows dated
    before that are the caller's to leave in place (see rebuild_history_stream_from's delete
    predicate). Progress is (current, total) over the scanned tail's own activities, not the
    athlete's whole history. Mirrors backend_java's ThresholdHistoryCalculator.replayFrom - keep
    the two in sync when changing either.

    Seeding contract: the replay's state at any date is its sliding window of not-yet-expired,
    not-yet-dominated candidates plus the moving sanity reference (the window's best). At the
    first date >= from_date every candidate still in that window is dated after
    `from_date - threshold_window_days` (older ones have aged out by definition), so the scan
    below covers every candidate that can matter in the tail. The part of the window dated before
    the scan start is seeded from the ledger itself: every measured row and every surviving (not
    correction-only) manual row with effective_from before the scan start re-enters as a window
    candidate at its own effective_from. That is exact, not an approximation: a pre-scan
    candidate that never became current (and so has no row of its own) can only matter in the
    tail if it later becomes the window's best, which is impossible while a better candidate
    outlives it - and any such better candidate either has its own row (seeded here, dated later,
    so it outlives the shadowed one) or is itself a scan candidate that re-removes it on arrival.
    A pre-scan candidate rejected by the sanity band never entered the window in the first place,
    so it's absent from both.

    The moving reference is initialised to the seeded window's best (aged as of the first
    scanned activity) and then tracked exactly like _replay_full_history_into's - including
    updating it when a manual candidate takes over without emitting an entry. Entries are only
    emitted for dates >= from_date: value changes discovered during the pre-from_date part of the
    scan correspond to rows that already exist with current_from < from_date and were not
    deleted, so re-emitting them would duplicate."""
    window_days = athlete.threshold_window_days
    scan_start = from_date - timedelta(days=window_days)

    window: list[Candidate] = []
    for row in ThresholdHistory.objects.filter(
        athlete=athlete, field=field, kind="measured", effective_from__lt=scan_start
    ).order_by("effective_from"):
        value = _row_value(field, row)
        if value is not None:
            window.append(Candidate(activity_id=row.source_activity_id, date=row.effective_from, implied_value=value))

    manual_rows = list(
        ThresholdHistory.objects.filter(athlete=athlete, field=field, kind="manual", is_correction_only=False).order_by(
            "effective_from"
        )
    )
    next_manual_idx = 0
    while next_manual_idx < len(manual_rows) and manual_rows[next_manual_idx].effective_from <= scan_start:
        manual_row = manual_rows[next_manual_idx]
        next_manual_idx += 1
        manual_value = _row_value(field, manual_row)
        if manual_value is not None:
            window.append(Candidate(activity_id=None, date=manual_row.effective_from, implied_value=manual_value))

    accepted_ids = _accepted_activity_ids(athlete, field)
    activities = list(
        Activity.objects.filter(
            athlete=athlete, sport=FIELD_SPORT[field], primary_activity__isnull=True, start_date__date__gte=scan_start
        ).order_by("start_date")
    )
    total = len(activities)
    if not activities:
        return

    # The moving sanity reference as of the first scanned activity: apply that date's own manual
    # merge and aging pass to the seeded window first (a manual row dated between the scan start
    # and the first activity could be the better reference), then take its best.
    first_date = activities[0].start_date.date()
    while next_manual_idx < len(manual_rows) and manual_rows[next_manual_idx].effective_from <= first_date:
        manual_row = manual_rows[next_manual_idx]
        next_manual_idx += 1
        manual_value = _row_value(field, manual_row)
        if manual_value is not None:
            window = [c for c in window if not _is_better(field, manual_value, c.implied_value)]
            window.append(Candidate(activity_id=None, date=manual_row.effective_from, implied_value=manual_value))
    initial_cutoff = first_date - timedelta(days=window_days)
    window = [c for c in window if c.date > initial_cutoff]
    current_value = window[0].implied_value if window else None

    for i, activity in enumerate(activities):
        activity_date = activity.start_date.date()

        # Merge in every manual row whose effective_from has arrived as of this activity - same
        # ordering and effect as _replay_full_history_into's own merge (the pre-scan ones were
        # already seeded above, so the list starts at the first post-seed row).
        while next_manual_idx < len(manual_rows) and manual_rows[next_manual_idx].effective_from <= activity_date:
            manual_row = manual_rows[next_manual_idx]
            next_manual_idx += 1
            manual_value = _row_value(field, manual_row)
            if manual_value is not None:
                window = [c for c in window if not _is_better(field, manual_value, c.implied_value)]
                window.append(Candidate(activity_id=None, date=manual_row.effective_from, implied_value=manual_value))

        cutoff = activity_date - timedelta(days=window_days)
        window = [c for c in window if c.date > cutoff]

        implied = _implied_value(activity, field)
        qualifies = implied is not None and (
            activity.id in accepted_ids
            or _within_sanity_band(field, implied, current_value, athlete.threshold_sanity_pct)
        )
        if qualifies:
            candidate = Candidate(activity_id=activity.id, date=activity_date, implied_value=implied)
            window = [c for c in window if not _is_better(field, implied, c.implied_value)]
            window.append(candidate)

        best = window[0] if window else None
        new_value = best.implied_value if best else None
        if new_value != current_value:
            current_value = new_value
            # Only a measured winner (a real activity) needs an entry - and only from from_date
            # onward: a change discovered earlier corresponds to a row that already exists with
            # current_from < from_date and was deliberately left in place. A manual winner's own
            # row already exists untouched, same as _replay_full_history_into.
            if best is not None and best.activity_id is not None and activity_date >= from_date:
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


def _latest_entry(athlete: User, field: str) -> ThresholdHistory | None:
    """The row currently treated as "the" value for this field - every "what's current" lookup
    (is_stale, upcoming_drop, _manual_anchor_candidate, _recompute_and_record's own guard, and
    this function's own callers below) goes through here, so a correction-only row (issue #493 -
    a backdated manual edit that never actually took over, because a later row already existed or
    it was already outside the window the moment it was saved) is excluded once, for all of them.
    A correction-only row is still a real row - zones.reference_for's activity-scoped lookup
    (filtered on current_from, not effective_from) finds it like any other - it's just never
    "the" value here."""
    return (
        ThresholdHistory.objects.filter(athlete=athlete, field=field)
        .exclude(is_correction_only=True)
        .order_by("-effective_from")
        .first()
    )


def _value_in_effect_at(athlete: User, field: str, as_of: date) -> float | None:
    """The ledger value already in effect on `as_of` - the same current_from <= date lookup
    zones.reference_for uses for an activity's own date, just keyed on an arbitrary date instead
    of an activity's. Unlike _latest_entry (the "what's current today" rule), this deliberately
    does NOT exclude correction-only rows - it's used to decide whether a backdated edit is a
    no-op (does it match what a ride on that date would already be rated against), which is
    exactly what a correction-only row's current_from already governs."""
    entry = (
        ThresholdHistory.objects.filter(athlete=athlete, field=field, current_from__lte=as_of)
        .order_by("-current_from")
        .first()
    )
    if entry is None:
        return None
    return _mmss_to_seconds(entry.value_pace) if field == "threshold_pace" else entry.value_numeric


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
        kind="measured",
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

    Decay-enabled (issue #485, slice 3): re-simulates the field's ledger suffix from this
    activity's own date instead - see threshold_decay_recompute.resimulate_suffix. A decay-
    disabled athlete (the default for every existing one) keeps the cliff-based path above
    entirely unchanged.
    """
    athlete = activity.athlete
    for field, sport in FIELD_SPORT.items():
        if sport == activity.sport:
            if athlete.threshold_decay_enabled:
                from .threshold_decay_recompute import resimulate_suffix

                resimulate_suffix(athlete, field, activity.start_date.date())
            else:
                _recompute_and_record(athlete, field, as_of=activity.start_date.date())


@dataclass(frozen=True)
class RerateDiff:
    activity_id: str
    date: date
    old_intensity: float | None
    new_intensity: float | None
    old_tss: int | None
    new_tss: int | None


@dataclass(frozen=True)
class ManualValueResult:
    changed: bool
    became_current: bool
    is_correction_only: bool
    rerated_count: int = 0
    would_rerate: list[RerateDiff] = dataclass_field(default_factory=list)


def record_manual_value(
    athlete: User,
    field: str,
    raw_value: int | str,
    set_by: User | None = None,
    effective_from: date | None = None,
    rerate_activities: bool = True,
    preview: bool = False,
    as_of: date | None = None,
) -> ManualValueResult:
    """The athlete directly declared this value via their profile (PATCH /v1/athletes/<id>), or
    had it set on their behalf by a coach with write access - trusted unconditionally (no
    sanity-band check, no window search: an explicit choice, not a computed candidate). Behaves
    exactly like any other ledger entry from here on: ages out after threshold_window_days like
    any other, and can be superseded by a later qualifying activity or another manual edit. A
    no-op if the submitted value already matches what's in effect at `effective_from` (e.g.
    re-saving the profile form without touching this field - the frontend always resubmits every
    field). Owns the athlete's cached <field> value entirely (issue #493) - never written by the
    caller; see the "became current" logic below for why.

    `effective_from` (issue #493) lets the edit be backdated instead of always applying from
    today. The row is always written - it's a legitimate historical correction either way - but
    it only becomes the live/current value (`became_current`) when BOTH (a) no existing row has
    a later effective_from, and (b) it isn't already outside threshold_window_days as of today
    (i.e. it wouldn't be stale the moment it's saved). Otherwise it's `is_correction_only`: still
    used by any activity-scoped lookup (zones.py::reference_for, filtered on current_from), just
    excluded from every "what's current" lookup (_latest_entry and everything built on it).
    Raises ValidationError for a future date or a date before the athlete's first activity of
    this field's sport - a date outside the window is allowed (that's exactly what makes it
    correction-only, not an error).

    `rerate_activities` (default True, matching this function's pre-#493 unconditional behavior)
    re-rates (intensity + tss) every activity of the affected sport on/after `effective_from` -
    see _rerate_activities_from. `preview` runs the whole write (ledger row, live-field update,
    re-rate) against the real code path and then rolls it back, so the returned diff is
    guaranteed identical to a real run's, with nothing persisted.

    `set_by` is the acting principal - equal to `athlete` for a self-edit, a different User for a
    coach acting on the athlete's behalf, or None only for the auto-derived path (which goes
    through _record_candidate instead, never through here). `as_of` overrides what "today" is
    for this call (test determinism only - real callers never pass it)."""
    implied_value = _mmss_to_seconds(raw_value) if field == "threshold_pace" else raw_value
    if not implied_value:
        return ManualValueResult(changed=False, became_current=False, is_correction_only=False)

    today = as_of or date.today()
    given_date = effective_from or today
    if given_date > today:
        raise ValidationError({"effective_from": "Must not be in the future."})

    first_activity_start = (
        Activity.objects.filter(athlete=athlete, sport=FIELD_SPORT[field])
        .order_by("start_date")
        .values_list("start_date", flat=True)
        .first()
    )
    if first_activity_start is not None and given_date < first_activity_start.date():
        raise ValidationError({"effective_from": "Must not be before the athlete's first activity of this sport."})

    existing_value = _value_in_effect_at(athlete, field, given_date)
    if existing_value == implied_value:
        return ManualValueResult(changed=False, became_current=False, is_correction_only=False)

    has_later_row = ThresholdHistory.objects.filter(
        athlete=athlete, field=field, effective_from__gt=given_date
    ).exists()
    stale_on_arrival = (today - given_date).days > athlete.threshold_window_days
    is_correction_only = has_later_row or stale_on_arrival
    became_current = not is_correction_only

    # Decay-enabled, and this edit actually becomes the live value: let resimulate_suffix create
    # the ledger row itself (via simulate's own manual-entry Step emission) and re-derive the
    # suffix after it, rather than writing it directly and separately re-simulating - the design
    # doc's "one mechanism" (4.2). A correction-only edit never competed to be current (same as
    # today), so it stays on the direct-write path below regardless of threshold_decay_enabled -
    # there's no suffix to re-simulate for a row that was never live in the first place.
    if athlete.threshold_decay_enabled and became_current:
        from .threshold_decay import ManualEntry as DecayManualEntry
        from .threshold_decay_recompute import resimulate_suffix

        new_entry = DecayManualEntry(date=given_date, value=implied_value, already_recorded=False)
        with transaction.atomic():
            result = resimulate_suffix(
                athlete,
                field,
                given_date,
                end=today,
                new_manual_entry=new_entry,
                manual_set_by=set_by,
                rerate_activities=rerate_activities,
                preview=preview,
            )
            if preview:
                transaction.set_rollback(True)
        return ManualValueResult(
            changed=True,
            became_current=True,
            is_correction_only=False,
            rerated_count=result.rerated_count,
            would_rerate=result.would_rerate,
        )

    value_numeric, value_pace = (None, raw_value) if field == "threshold_pace" else (raw_value, "")

    with transaction.atomic():
        ThresholdHistory.objects.create(
            athlete=athlete,
            field=field,
            value_numeric=value_numeric,
            value_pace=value_pace,
            source_activity=None,
            set_by=set_by,
            effective_from=given_date,
            current_from=given_date,
            kind="manual",
            is_correction_only=is_correction_only,
        )

        rerated_count, would_rerate = _rerate_activities_from(
            athlete, field, given_date, persist=rerate_activities, collect_diff=preview
        )

        # Never touched by the caller (unlike pre-#493) - became_current already accounts for
        # whether this edit is actually the one that should be live, so this is always right,
        # including a no-op-looking re-derivation when it isn't this edit's row that's current.
        if not preview:
            current = _latest_entry(athlete, field)
            live_value = (
                current.value_pace
                if (current and field == "threshold_pace")
                else (current.value_numeric if current else None)
            )
            setattr(athlete, field, live_value)
            athlete.save(update_fields=[field])

        if preview:
            # Rolls back to this call's own savepoint only - whatever the caller's own enclosing
            # transaction does for other, unrelated fields in the same request is untouched.
            transaction.set_rollback(True)

    return ManualValueResult(
        changed=True,
        became_current=became_current,
        is_correction_only=is_correction_only,
        rerated_count=rerated_count,
        would_rerate=would_rerate,
    )


def _rerate_activities_from(
    athlete: User, field: str, effective_from: date, *, persist: bool, collect_diff: bool
) -> tuple[int, list[RerateDiff]]:
    """Re-rates (intensity + tss) every one of the athlete's activities of the affected sport
    dated on/after a manual threshold edit's effective date - otherwise they'd stay scored
    against the now-superseded value until someone happens to press a recompute button (issue
    #481, part 3). No upper bound needed, even once effective_from can be backdated (issue #493):
    each activity's own reference_for lookup (filtered on current_from) already stops applying
    this edit once a later ledger row's current_from takes over for that date, so an activity
    past that boundary just re-rates to the value it already had - rate_activity only reports a
    change when something actually did. Same multisport-parent exclusion
    athletes.views.RecomputeAthleteTssView already uses - local import to avoid a circular import
    (uploads.processing imports this module at the top level for recompute_for_activity).

    `persist` actually saves a changed activity (the real rerate_activities=True path).
    `collect_diff` returns a RerateDiff per changed activity regardless of `persist` (issue
    #493's preview, which wants to know what WOULD change even when the box isn't ticked).
    """
    if not persist and not collect_diff:
        return 0, []

    from uploads.processing import rate_activity

    candidates = Activity.objects.filter(
        athlete=athlete, sport=FIELD_SPORT[field], parent_activity__isnull=True, start_date__date__gte=effective_from
    )
    rerated_count = 0
    diffs: list[RerateDiff] = []
    for activity in candidates:
        old_intensity, old_tss = activity.intensity, activity.tss
        power_series = list(activity.records.order_by("t").values_list("power", flat=True))
        hr_series = list(activity.records.order_by("t").values_list("heartrate", flat=True))
        if not rate_activity(activity, athlete, power_series, hr_series):
            continue
        if collect_diff:
            diffs.append(
                RerateDiff(
                    activity.id, activity.start_date.date(), old_intensity, activity.intensity, old_tss, activity.tss
                )
            )
        if persist:
            activity.save(update_fields=["intensity", "tss"])
            rerated_count += 1
    return rerated_count, diffs


def refresh_field(athlete: User, field: str) -> bool:
    """Manual on-demand recompute (the dashboard's "this value is stale - refresh now" action) -
    the same cheap current-window computation as the ingest hook, just athlete-triggered rather
    than tied to a specific new activity. Returns whether the value actually changed.

    Decay-enabled: append-only re-simulation from today (design doc 4.2's "refresh button" row) -
    see threshold_decay_recompute.resimulate_suffix."""
    if athlete.threshold_decay_enabled:
        from .threshold_decay_recompute import resimulate_suffix

        return resimulate_suffix(athlete, field, date.today()).changed
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
    """The bulk 'recompute history from oldest' tool - replaces the *measured* portion of the
    ledger for one field with a fresh replay, then syncs the athlete's cached profile value to
    whatever is now current (or clears it if there's no qualifying row at all). A manual or
    correction-only row is never deleted or rederived here - it isn't something activities can
    reconstruct, so wiping it on every rebuild would silently discard a real, intentional
    correction (issue #504; #493 requires the opposite - "rebuild must keep backdated manual
    rows"). _replay_full_history_into already merges those surviving rows back into the window
    at their own effective_from, so measured activities are still correctly suppressed/revealed
    relative to them - see its own docstring. Yields (current, total) progress after each
    activity processed, for the SSE bulk-rebuild endpoint.

    Decay-enabled: delegates to threshold_decay_recompute.resimulate_suffix from the athlete's
    first activity of this field's sport, or an earlier surviving manual row's own date if one
    exists (true cold start - resimulate_suffix's own seed lookups must find nothing at all
    before this point, or a manual row older than the first activity would otherwise be picked
    up as a seed anchor instead of entering the day loop as a manual_entries event, same effect
    either way for *this* field's own ledger, but silently wrong once a real daily job starts
    relying on the seed lookup's "nothing before this date" assumption elsewhere). Design doc
    4.2's "rebuild" row - "measured" AND "decayed" rows are replaced, manual rows are still kept.
    resimulate_suffix isn't a generator (simulate's day loop has no natural per-activity hook),
    so this yields a single (1, 1) progress tuple instead of one per activity - a known,
    disclosed limitation of this slice, not a correctness gap."""
    if athlete.threshold_decay_enabled:
        from .threshold_decay_recompute import resimulate_suffix

        first_activity_date = (
            Activity.objects.filter(athlete=athlete, sport=FIELD_SPORT[field], primary_activity__isnull=True)
            .order_by("start_date")
            .values_list("start_date", flat=True)
            .first()
        )
        earliest_manual_date = (
            ThresholdHistory.objects.filter(athlete=athlete, field=field, kind="manual", is_correction_only=False)
            .order_by("effective_from")
            .values_list("effective_from", flat=True)
            .first()
        )
        candidates = [d for d in [first_activity_date and first_activity_date.date(), earliest_manual_date] if d]
        if candidates:
            resimulate_suffix(athlete, field, min(candidates))
        yield 1, 1
        return

    ThresholdHistory.objects.filter(athlete=athlete, field=field, kind="measured").delete()
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
            kind="measured",
        )
        for entry in entries
    )
    # The current row after the rebuild, not "the replay's last entry" - a still-valid manual
    # row (kept, not deleted above) can be more recent than any measured row the replay just
    # wrote, and _latest_entry already excludes a correction-only row from ever being mistaken
    # for current (same lookup _manual_anchor_candidate/is_stale use).
    current_row = _latest_entry(athlete, field)
    if field == "threshold_pace":
        setattr(athlete, field, current_row.value_pace if current_row is not None else "")
    else:
        setattr(athlete, field, current_row.value_numeric if current_row is not None else None)
    athlete.save(update_fields=[field])


def rebuild_history_stream_from(athlete: User, field: str, from_date: date) -> Iterator[tuple[int, int]]:
    """The from-date variant of rebuild_history_stream (issue #545): a duplicate-status change on
    an activity dated d can only alter the ledger from d onward, so this deletes only measured
    rows with current_from >= from_date and re-derives just that tail via replay_from. The delete
    is on current_from, not effective_from - a cascading-expiry row has effective_from before
    from_date but current_from after (it only became current during the tail), and deleting by
    effective_from would leave it behind as a stale duplicate of what the tail replay re-derives.
    Manual and correction-only rows are never touched, same as rebuild_history_stream (issue
    #504/#493). No TSS re-rate here - the caller (uploads.processing.recompute_after_duplicate_
    change) re-rates once for all changed fields, from the same date. Mirrors backend_java's
    ThresholdHistoryService.rebuildHistoryFrom - keep the two in sync when changing either.

    Decay-enabled: same delegation as rebuild_history_stream's decay branch, but from the given
    date rather than a cold start - resimulate_suffix's own seed lookups find the pre-from_date
    state. rerate_activities=False: the caller re-rates once for all changed fields, so this
    yields a single (1, 1) progress tuple instead of one per activity, same disclosed limitation
    as rebuild_history_stream's own decay branch."""
    if athlete.threshold_decay_enabled:
        from .threshold_decay_recompute import resimulate_suffix

        resimulate_suffix(athlete, field, from_date, rerate_activities=False)
        yield 1, 1
        return

    ThresholdHistory.objects.filter(athlete=athlete, field=field, kind="measured", current_from__gte=from_date).delete()
    entries: list[ThresholdHistoryEntry] = []
    yield from _replay_from_into(athlete, field, from_date, entries)

    ThresholdHistory.objects.bulk_create(
        ThresholdHistory(
            athlete=athlete,
            field=field,
            value_numeric=None if field == "threshold_pace" else round(entry.value),
            value_pace=_seconds_to_mmss(entry.value) if field == "threshold_pace" else "",
            source_activity_id=entry.activity_id,
            effective_from=entry.effective_from,
            current_from=entry.current_from,
            kind="measured",
        )
        for entry in entries
    )
    # Same current-row re-derivation as rebuild_history_stream - a still-valid manual row (kept,
    # not deleted above) can be more recent than any measured row the tail replay just wrote.
    current_row = _latest_entry(athlete, field)
    if field == "threshold_pace":
        setattr(athlete, field, current_row.value_pace if current_row is not None else "")
    else:
        setattr(athlete, field, current_row.value_numeric if current_row is not None else None)
    athlete.save(update_fields=[field])


# --- Threshold suggestions: detection only (pure/read-only, like the rest of this module) -----
# Response formatting, dismissal filtering, and caching live in threshold_suggestions.py, which
# is also where the AcceptedThresholdCandidate/ThresholdSuggestionDismissal models get consumed
# for anything beyond the sanity-band bypass above.


@dataclass(frozen=True)
class RejectedCandidate:
    activity_id: str
    date: date
    implied_value: float
    window: str
    raw_value: float


def rejected_candidates(athlete: User, field: str, as_of: date | None = None) -> list[RejectedCandidate]:
    """Qualifying efforts that *beat* the athlete's current value but were excluded by the
    sanity band (threshold_sanity_pct) - a real breakthrough, or a recalibrated/corrupt sensor;
    the athlete decides (see the Threshold suggestions feature). Same trailing-window scan as
    current_window_value, but keeps exactly what that one excludes: upward outliers only (a
    corrupt-LOW reading is simply wrong, not an actionable "maybe you're fitter now" suggestion -
    _within_sanity_band already treats it identically to a corrupt-high one, so the direction
    check here is what narrows to just the interesting case). Already-accepted candidates (see
    AcceptedThresholdCandidate) are excluded - they're part of the real ledger now, not a pending
    suggestion. Returns every qualifying candidate, best first."""
    as_of = as_of or date.today()
    window_start = as_of - timedelta(days=athlete.threshold_window_days)
    reference_value = _athlete_reference_value(athlete, field)
    accepted_ids = _accepted_activity_ids(athlete, field)

    activities = list(
        Activity.objects.filter(
            athlete=athlete,
            sport=FIELD_SPORT[field],
            primary_activity__isnull=True,
            start_date__date__gte=window_start,
            start_date__date__lte=as_of,
        ).order_by("start_date")
    )

    candidates: list[RejectedCandidate] = []
    for chunk in _chunked(activities):
        batched_records = _batched_records_for(chunk)
        for activity in chunk:
            if activity.id in accepted_ids:
                continue
            try:
                detail = _implied_value_detail(activity, field, batched_records.get(activity.id, []))
            except Exception:
                # One bad activity's stream shouldn't take down the whole suggestions scan - skip
                # it and keep going (issue #500's class of bug, applied defensively here too even
                # without a reproduced trigger for it).
                continue
            if detail is None:
                continue
            if _within_sanity_band(field, detail.value, reference_value, athlete.threshold_sanity_pct):
                continue
            # Reaching here already proves reference_value is a real, non-zero number - the
            # sanity band above only ever fails when it is (see _within_sanity_band's own
            # None/0 passthrough).
            if not _is_better(field, detail.value, reference_value):
                continue
            candidates.append(
                RejectedCandidate(
                    activity_id=activity.id,
                    date=activity.start_date.date(),
                    implied_value=detail.value,
                    window=detail.window,
                    raw_value=detail.raw_value,
                )
            )

    candidates.sort(key=lambda c: c.implied_value, reverse=field not in LOWER_IS_BETTER)
    return candidates


@dataclass(frozen=True)
class CandidateStatus:
    activity_id: str
    date: date
    status: str  # "qualified" | "rejected_sanity" | "accepted" | "too_short" | "error"
    implied_value: float | None
    raw_value: float | None
    window: str | None
    # Set only for status="error" - one activity's implied-value computation raised, surfaced
    # here instead of failing candidate_statuses' whole call (issue #500).
    error_reason: str | None = None


def candidate_statuses(
    athlete: User, field: str, from_date: date, to_date: date, include_duplicates: bool = False
) -> list[CandidateStatus]:
    """Every one of the athlete's activities of `field`'s sport in [from_date, to_date],
    classified against the athlete's *current* profile value - a single snapshot, not a moving
    reference (see current_window_value's own docstring for that distinction): 'qualified'
    (within the sanity band), 'rejected_sanity' (outside it), 'accepted' (explicitly accepted
    despite failing the band - see AcceptedThresholdCandidate), or 'too_short' (no qualifying
    effort at all, e.g. too short for the field's window). Unlike rejected_candidates, returns
    every activity in range, not just the rejected-and-better subset - built for the Threshold
    suggestions calibration tooling (list_threshold_candidates), to see what a model would have
    had to predict between ledger entries. A per-activity computation failure is surfaced as
    status="error" with error_reason set, rather than failing the whole call (issue #500).
    `include_duplicates` (issue #498) is for ad-hoc inspection of a same-ride-twice pair via
    list_threshold_candidates only - every other caller leaves it at the default, which excludes
    a duplicate-marked activity exactly like current_window_value/rejected_candidates do."""
    reference_value = _athlete_reference_value(athlete, field)
    accepted_ids = _accepted_activity_ids(athlete, field)

    qs = Activity.objects.filter(
        athlete=athlete,
        sport=FIELD_SPORT[field],
        start_date__date__gte=from_date,
        start_date__date__lte=to_date,
    )
    if not include_duplicates:
        qs = qs.filter(primary_activity__isnull=True)
    activities = list(qs.order_by("start_date"))

    statuses: list[CandidateStatus] = []
    for chunk in _chunked(activities):
        batched_records = _batched_records_for(chunk)
        for activity in chunk:
            activity_date = activity.start_date.date()
            try:
                detail = _implied_value_detail(activity, field, batched_records.get(activity.id, []))
            except Exception as e:
                statuses.append(CandidateStatus(activity.id, activity_date, "error", None, None, None, str(e)))
                continue
            if detail is None:
                statuses.append(CandidateStatus(activity.id, activity_date, "too_short", None, None, None))
                continue
            if activity.id in accepted_ids:
                status = "accepted"
            elif _within_sanity_band(field, detail.value, reference_value, athlete.threshold_sanity_pct):
                status = "qualified"
            else:
                status = "rejected_sanity"
            statuses.append(
                CandidateStatus(activity.id, activity_date, status, detail.value, detail.raw_value, detail.window)
            )
    return statuses


def warning_lead_days(athlete: User) -> int:
    """How many days before a threshold's current source activity ages out of the window the
    Threshold suggestions feature warns the athlete - User.threshold_warning_days, scaled down
    for an athlete with a shorter-than-typical window (otherwise a long lead on a short window
    could warn before the value has even had a real chance to establish itself)."""
    if athlete.threshold_window_days >= 84:
        return athlete.threshold_warning_days
    return athlete.threshold_window_days // 4


def upcoming_drop(athlete: User, field: str, today: date | None = None) -> dict | None:
    """Warns the athlete when the best effort behind the current threshold value is about to age
    out of the trailing window, before the value silently drops - enough lead time to test or
    race for a fresh one (see the Threshold suggestions feature). Returns None when there's
    nothing to warn about (not yet/no longer in the lead window, warnings off, or a small-drop/
    near-match suppression rule applies); otherwise a dict of raw internal values - not API-
    shaped primitives, see threshold_suggestions.py for that layer:
      {"kind": "upcoming_drop", "current_entry": ThresholdHistory, "successor": Candidate | None,
       "expiry": date, "days_left": int}
      {"kind": "race_will_refresh", "race": Race, "expiry": date, "days_left": int}
    """
    today = today or date.today()
    lead = warning_lead_days(athlete)
    if lead <= 0:
        return None

    latest = _latest_entry(athlete, field)
    if latest is None or is_stale(athlete, field, today):
        return None

    # is_stale() flips the day *after* threshold_window_days have elapsed since effective_from
    # (see its own ".days > ..." comparison), so the window's actual last covered day is one
    # later than a naive +threshold_window_days would give.
    expiry = latest.effective_from + timedelta(days=athlete.threshold_window_days + 1)
    days_left = (expiry - today).days
    if days_left <= 0 or days_left > lead:
        return None

    successor = current_window_value(athlete, field, as_of=expiry)
    current_value = _athlete_reference_value(athlete, field)

    # Rule 1: small drop - close enough that a warning isn't worth the noise.
    if successor is not None and current_value is not None and current_value != 0:
        delta = abs(successor.implied_value - current_value)
        delta_pct = delta / current_value * 100
        small_absolute = 3 if field in LOWER_IS_BETTER else 5
        if delta_pct <= 2 and delta < small_absolute:
            return None

    # Rule 2: near match - a recent activity (last 14 days) already implies a value close to
    # current, so it's likely to naturally replace it before the drop happens anyway.
    if current_value is not None and current_value != 0:
        recent_start = today - timedelta(days=14)
        recent_activities = Activity.objects.filter(
            athlete=athlete,
            sport=FIELD_SPORT[field],
            primary_activity__isnull=True,
            start_date__date__gte=recent_start,
            start_date__date__lte=today,
        )
        for activity in recent_activities:
            implied = _implied_value(activity, field)
            if implied is not None and abs(implied - current_value) / current_value <= 0.02:
                return None

    # Rule 3: race booked - a race in the field's sport before expiry should naturally refresh
    # the value; the caller turns this into a quiet note instead of a warning.
    race = (
        Race.objects.filter(athlete=athlete, sport=FIELD_SPORT[field], date__gte=today, date__lt=expiry)
        .order_by("date")
        .first()
    )
    if race is not None:
        return {"kind": "race_will_refresh", "race": race, "expiry": expiry, "days_left": days_left}

    # Rule 4: no successor - emit anyway (successor=None); the caller formats this as "goes
    # stale" rather than "drops to {value}".
    return {
        "kind": "upcoming_drop",
        "current_entry": latest,
        "successor": successor,
        "expiry": expiry,
        "days_left": days_left,
    }
