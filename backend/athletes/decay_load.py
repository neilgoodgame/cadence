"""The load term for threshold decay (issue #485, docs/plans/threshold-decay.md section 2.3):
intensity-filtered training load, measured against the anchor, feeding threshold_decay.simulate's
`load_by_day` input. `activity_load`/`decay_load_series` are pure, reading only the per-activity
histograms activities.intensity_histogram already builds - no Record re-reads needed, which is
exactly why that histogram stores seconds-per-watt rather than a raw series.

`load_by_day_for_athlete` is the one DB-touching piece here, and deliberately thin: it's read-only
(no locking, no writes beyond histogram lazy-backfill, which is already idempotent) and doesn't
decide *which* anchor value to measure against - the caller (deferred to the next #485 slice,
which wires this into the real recompute paths) passes in whatever anchor was in effect at the
relevant point in time, since that's a wiring-layer decision this module has no business making.
"""

from __future__ import annotations

from datetime import date, timedelta

from accounts.models import User
from activities.intensity_histogram import ensure_histogram
from activities.models import Activity

from .threshold_history import FIELD_SPORT, _excluded_activity_ids
from .zones import reference_for

# field -> the zone_type whose percentages are relative to that field - the inverse of
# athletes.zones.THRESHOLD_FIELD_BY_ZONE_TYPE, restricted to the three fields FIELD_SPORT covers
# (heart_rate/lthr has no per-activity history to resolve against - see reference_for's own
# docstring - and isn't a threshold-decay field at all).
_ZONE_TYPE_BY_FIELD = {"ftp": "bike_power", "critical_run_power": "run_power", "threshold_pace": "pace"}

# How far above Z2 counts toward the load (0.75 x anchor) - resolved open question 9 of the design
# doc: ship the hard cutoff, not the smooth-ramp alternative; the histogram means switching later
# needs no reprocessing.
DEFAULT_CUTOFF = 0.75
# HR fallback cutoff for activities without trusted power - proposed, uncalibrated (open question
# 8); used as-is per the doc's own recommendation to check it against real HR+power rides before
# this feeds anything user-visible, which is deferred to a later slice.
DEFAULT_HR_CUTOFF = 0.85
LOAD_EWMA_DAYS = 42


def activity_load(
    power_30s_histogram: dict | None,
    heartrate_histogram: dict | None,
    anchor_value: float,
    lthr: float | None,
    cutoff: float = DEFAULT_CUTOFF,
    hr_cutoff: float = DEFAULT_HR_CUTOFF,
) -> float:
    """One activity's contribution to the decay load - `Sigma_watt seconds_at_watt * w(watt/R) *
    (watt/R)^2 * 100/3600` (the section 2.3 formula, computed directly from the histogram rather
    than a raw series), falling back to the heart-rate histogram against `lthr` only when there's
    no usable power histogram. Histogram keys may be ints (freshly built) or strings (after a
    JSONField round trip) - coerced to int either way, same value either way."""
    if power_30s_histogram and anchor_value:
        total = 0.0
        for watt, seconds in power_30s_histogram.items():
            ratio = int(watt) / anchor_value
            if ratio >= cutoff:
                total += (ratio**2) * 100 / 3600 * seconds
        return total
    if heartrate_histogram and lthr:
        total = 0.0
        for bpm, seconds in heartrate_histogram.items():
            ratio = int(bpm) / lthr
            if ratio >= hr_cutoff:
                total += (ratio**2) * 100 / 3600 * seconds
        return total
    return 0.0


def above_z2_load_for_activity(
    activity: Activity, athlete: User, field: str, reference: int | None = None
) -> float | None:
    """Issue #487: activity_load() for exactly one activity, resolving its own histogram and
    reference threshold - the per-activity number list_activity_above_z2_load exposes directly,
    and the per-day building block compute_above_z2_fitness_series sums into a CTL/ATL series.
    athlete is taken explicitly rather than read off activity.athlete - list_activity_above_z2_load
    calls this once per activity in a (possibly large) queryset, and the caller already has the
    athlete loaded, so reading it off each activity instead would re-fetch the same User row once
    per activity (an N+1 query pattern); matches load_by_day_for_athlete's own convention, and the
    Java mirror's equivalent fix for an analogous lazy-association problem. reference overrides
    the activity's own in-effect anchor (reference_for) when given, e.g. to ask "what if FTP had
    been X the whole time" for calibration - never applies to the HR fallback's own lthr, which
    (like load_by_day_for_athlete) always reads the athlete's current stored value. Returns None
    (not 0.0) when there's no histogram data at all, or no reference to measure it against -
    activity_load() itself conflates either of those with "zero load above cutoff", this wrapper
    doesn't.

    For field="threshold_pace", reference_for resolves a pace-in-seconds number - if the same
    activity also has a power histogram, activity_load's power branch would divide watts by that
    pace number, which is nonsensical. This is pre-existing behavior load_by_day_for_athlete
    already has for this field (there's no pace-based histogram, and no power/pace cross-check
    anywhere in this module) - not something this wrapper introduces or fixes."""
    histogram = ensure_histogram(activity)
    # Same HR fallback as load_by_day_for_athlete for an activity excluded from this field's
    # threshold detection (issue #616).
    power = None if activity.id in _excluded_activity_ids(athlete, field) else histogram.power_30s
    if not power and not histogram.heartrate:
        return None
    effective_reference = (
        reference if reference is not None else reference_for(athlete, _ZONE_TYPE_BY_FIELD[field], activity)
    )
    if not effective_reference:
        return None
    return activity_load(power, histogram.heartrate, effective_reference, athlete.lthr)


def decay_load_series(daily_loads: dict[date, float]) -> dict[date, float]:
    """The 42-day EWMA of daily load - same recurrence as core.derived.compute_fitness_series's
    CTL (`x += (new - x) / days`, walked day by day from a true 0 baseline), duplicated locally
    rather than reused: that function is hardwired to TSS-by-day queries, not a reusable
    EWMA(dict, days) helper (matches this codebase's own convention of small local duplicates over
    reaching into another module's implementation detail - see threshold_history.py's and
    TssCalculator's own docstrings for the same call). Walks every day from the earliest to the
    latest key in `daily_loads`, treating a missing day as zero load."""
    if not daily_loads:
        return {}
    start, end = min(daily_loads), max(daily_loads)
    result: dict[date, float] = {}
    load = 0.0
    d = start
    one_day = timedelta(days=1)
    while d <= end:
        load += (daily_loads.get(d, 0.0) - load) / LOAD_EWMA_DAYS
        result[d] = load
        d += one_day
    return result


def load_by_day_for_athlete(
    athlete: User, field: str, anchor_value: float, lthr: float | None, start: date, end: date
) -> dict[date, float]:
    """`load_by_day` for threshold_decay.simulate, for real: every qualifying activity (same
    sport/primary_activity__isnull filter current_window_value already uses) in `[start, end]`,
    summed per day via activity_load against the given anchor, then smoothed via
    decay_load_series. Lazily backfills any activity's histogram that doesn't exist yet
    (ensure_histogram) - idempotent, same as ensure_elevation's own lazy build, so no locking is
    needed here."""
    activities = Activity.objects.filter(
        athlete=athlete,
        sport=FIELD_SPORT[field],
        primary_activity__isnull=True,
        start_date__date__gte=start,
        start_date__date__lte=end,
    ).order_by("start_date")

    # An activity excluded from threshold detection for this field (issue #616) has untrustworthy
    # power, so it counts through its heart rate instead - activity_load's own fallback for an
    # activity without usable power. The session still counts as training.
    excluded_ids = _excluded_activity_ids(athlete, field)
    daily_loads: dict[date, float] = {}
    for activity in activities:
        histogram = ensure_histogram(activity)
        power = None if activity.id in excluded_ids else histogram.power_30s
        load = activity_load(power, histogram.heartrate, anchor_value, lthr)
        activity_date = activity.start_date.date()
        daily_loads[activity_date] = daily_loads.get(activity_date, 0.0) + load

    return decay_load_series(daily_loads)
