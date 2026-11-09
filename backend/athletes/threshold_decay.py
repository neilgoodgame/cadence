"""Pure day-by-day threshold decay simulation (issue #485, slice 1 of the threshold-decay plan
at docs/plans/threshold-decay.md). Gives a threshold value a gradual decay curve once its
anchoring evidence ages out of the trailing window, instead of the cliff `threshold_history.py`'s
`current_window_value`/`replay_full_history` produce today when a better-but-now-stale candidate
simply falls out of the window and the next-best one (however much lower) takes over immediately.

Pure/read-only, no ORM - unit-testable with no database, mirroring `TssCalculator`'s static shape
(see `backend_java/.../athletes/ThresholdDecay.java`). Everything here operates on plain inputs
the caller already has; wiring this into ingest/manual-edit/refresh/rebuild, the intensity
histogram that would produce `load_by_day` for real, and the daily cron job that would call this
for every athlete are all deferred to a follow-up slice - see the plan doc's "explicitly out of
scope" list.

All arithmetic happens in **effort units** (`e = 1/value` for `threshold_pace`, since a lower
pace is the improvement there; `e = value` for every other field, where higher is the
improvement) rather than native units, so the single recurrence below doesn't need a field-
specific branch: "higher effort is always better" holds uniformly, including for the `x >=
round(v)` upward-reset test and the `min(v, ...)` "never eases upward" clamp. Native units (watts,
or whole seconds/km for pace) are only ever used at the boundary - inputs and `Step.value`.

**The `start`/seed contract**: `start` must be no later than `seed_anchor_date +
settings.window_days + 1` (the anchor's own expiry) - i.e. `v` has not yet started decaying away
from the seed anchor's value by the time `start` is reached. In practice every caller passes
`start == seed_anchor_date` (resuming right at the anchor that's already on file) or an earlier
date (a cold start with no prior anchor, `seed_anchor_value=None`). `candidates`/`manual_entries`
must NOT include whatever activity/edit already produced the seed anchor itself - only genuinely
new evidence the caller hasn't already folded into the seed. They should, however, include
anything from up to `settings.window_days` before `start` that's still within the trailing window,
so the day loop's own sliding-window successor tracking (see `DecayCandidate`) starts correctly
populated rather than empty.
"""

from __future__ import annotations

import math
from collections import defaultdict
from dataclasses import dataclass
from datetime import date, timedelta

# Pace is seconds/km - a lower value is the improvement, the opposite of every other field (same
# distinction threshold_history.py's own LOWER_IS_BETTER draws) - but here it only ever matters
# for the effort-unit conversion, not as a separate comparison branch (see module docstring).
_PACE_FIELD = "threshold_pace"

MEASURED = "measured"
MANUAL = "manual"
DECAYED = "decayed"


@dataclass(frozen=True)
class DecaySettings:
    window_days: int
    sanity_pct: int
    tau_days: float
    min_pct: float
    max_pct: float
    enabled: bool


@dataclass(frozen=True)
class DecayCandidate:
    """A qualifying activity's implied value for `field` on `date` - native units, already
    rounded (as `threshold_history._implied_value_detail` already does). `accepted` bypasses the
    sanity band, mirroring `AcceptedThresholdCandidate`."""

    date: date
    implied_value: float
    activity_id: str | None
    accepted: bool = False


@dataclass(frozen=True)
class ManualEntry:
    """An athlete- or coach-entered value, native units - always becomes the anchor on its own
    date regardless of whether it's higher or lower than the current value (unlike a measured
    candidate's upward-reset test), matching the existing `record_manual_value` rule."""

    date: date
    value: float
    activity_id: str | None = None


@dataclass(frozen=True)
class Step:
    """One ledger row `simulate` would write - native units, already rounded. `kind` is one of
    `MEASURED`/`MANUAL`/`DECAYED`."""

    value: float
    kind: str
    activity_id: str | None
    effective_from: date
    current_from: date


def _to_effort(field: str, value: float) -> float:
    return 1.0 / value if field == _PACE_FIELD else float(value)


def _from_effort(field: str, effort: float) -> float:
    return 1.0 / effort if field == _PACE_FIELD else effort


def _round_native(field: str, value: float) -> float:
    return float(round(value))


def _within_sanity_band(candidate_value: float, reference_value: float | None, sanity_pct: int) -> bool:
    """Same rule as `threshold_history._within_sanity_band` - always true with no reference yet."""
    if reference_value is None or reference_value == 0:
        return True
    return abs(candidate_value - reference_value) / reference_value <= sanity_pct / 100


def _daterange(start: date, end: date):
    d = start
    one_day = timedelta(days=1)
    while d <= end:
        yield d
        d += one_day


def _group_by_day(items, key=lambda item: item.date):
    by_day: dict[date, list] = defaultdict(list)
    for item in sorted(items, key=key):
        by_day[key(item)].append(item)
    return by_day


def simulate(
    field: str,
    settings: DecaySettings,
    seed_anchor_value: float | None,
    seed_anchor_date: date | None,
    seed_anchor_kind: str | None,
    candidates: list[DecayCandidate],
    manual_entries: list[ManualEntry],
    load_by_day: dict[date, float],
    start: date,
    end: date,
) -> list[Step]:
    """The one entry point - see the module docstring for the effort-unit design and the
    `start`/seed contract. `seed_anchor_value`/`date`/`kind` describe the anchor already on file
    as of just before `start` (all `None` for a cold start with no prior anchor at all)."""
    candidates_by_day = _group_by_day(candidates)
    manual_by_day = {m.date: m for m in sorted(manual_entries, key=lambda m: m.date)}
    if settings.enabled:
        return _simulate_enabled(
            field,
            settings,
            seed_anchor_value,
            seed_anchor_date,
            candidates_by_day,
            manual_by_day,
            load_by_day,
            start,
            end,
        )
    return _simulate_disabled(
        field,
        settings,
        seed_anchor_value,
        seed_anchor_date,
        seed_anchor_kind,
        candidates_by_day,
        manual_by_day,
        start,
        end,
    )


def _simulate_enabled(
    field: str,
    settings: DecaySettings,
    seed_anchor_value: float | None,
    seed_anchor_date: date | None,
    candidates_by_day: dict[date, list[DecayCandidate]],
    manual_by_day: dict[date, ManualEntry],
    load_by_day: dict[date, float],
    start: date,
    end: date,
) -> list[Step]:
    a = seed_anchor_date
    anchor_effort = _to_effort(field, seed_anchor_value) if seed_anchor_value is not None else None
    v_effort = anchor_effort
    # The successor pool: qualifying *activities* only (not manual entries - see 2.1 of the plan
    # doc), sliding-window-maximum pruned exactly like threshold_history.py's own window scan.
    window: list[tuple[date, float, str | None]] = []
    last_emitted_native = (
        _round_native(field, _from_effort(field, anchor_effort)) if anchor_effort is not None else None
    )
    steps: list[Step] = []

    loop_start = min(start, seed_anchor_date) if seed_anchor_date is not None else start
    for d in _daterange(loop_start, end):
        anchor_event: tuple[str, str | None, date] | None = None

        manual = manual_by_day.get(d)
        if manual is not None:
            anchor_effort = _to_effort(field, manual.value)
            a = d
            v_effort = anchor_effort
            anchor_event = (MANUAL, manual.activity_id, d)

        ref_native = _round_native(field, _from_effort(field, v_effort)) if v_effort is not None else None
        for candidate in candidates_by_day.get(d, []):
            if (
                not candidate.accepted
                and ref_native is not None
                and not _within_sanity_band(candidate.implied_value, ref_native, settings.sanity_pct)
            ):
                continue
            candidate_effort = _to_effort(field, candidate.implied_value)
            window = [w for w in window if w[1] >= candidate_effort]
            window.append((d, candidate_effort, candidate.activity_id))
            is_upward_reset = v_effort is None or candidate_effort >= _to_effort(
                field, _round_native(field, _from_effort(field, v_effort))
            )
            if is_upward_reset:
                anchor_effort = candidate_effort
                a = d
                v_effort = candidate_effort
                anchor_event = (MEASURED, candidate.activity_id, d)
                ref_native = _round_native(field, _from_effort(field, v_effort))

        cutoff = d - timedelta(days=settings.window_days)
        window = [w for w in window if w[0] > cutoff]

        if v_effort is not None:
            expiry = a + timedelta(days=settings.window_days + 1)
            if d < expiry:
                v_effort = anchor_effort
            else:
                load_today = load_by_day.get(d, 0.0)
                load_at_anchor = load_by_day.get(a, 0.0)
                ratio = 1.0 if load_at_anchor < 1 else min(1.0, max(0.0, load_today / load_at_anchor))
                amplitude = settings.min_pct + (settings.max_pct - settings.min_pct) * (1 - ratio)
                floor_effort = anchor_effort * (1 - amplitude / 100.0)
                successor_effort = max((w[1] for w in window), default=None)
                target_effort = (
                    max(successor_effort, floor_effort)
                    if successor_effort is not None
                    else anchor_effort * (1 - settings.max_pct / 100.0)
                )
                decay_factor = math.exp(-1.0 / settings.tau_days)
                v_effort = min(v_effort, target_effort + (v_effort - target_effort) * decay_factor)

        if v_effort is not None and d >= start:
            new_native = _round_native(field, _from_effort(field, v_effort))
            if anchor_event is not None:
                kind, activity_id, effective_from = anchor_event
                steps.append(Step(new_native, kind, activity_id, effective_from, d))
                last_emitted_native = new_native
            elif last_emitted_native is None or new_native != last_emitted_native:
                steps.append(Step(new_native, DECAYED, None, d, d))
                last_emitted_native = new_native

    return steps


def _simulate_disabled(
    field: str,
    settings: DecaySettings,
    seed_anchor_value: float | None,
    seed_anchor_date: date | None,
    seed_anchor_kind: str | None,
    candidates_by_day: dict[date, list[DecayCandidate]],
    manual_by_day: dict[date, ManualEntry],
    start: date,
    end: date,
) -> list[Step]:
    """Decay off: reproduces today's behaviour (`threshold_history._replay_full_history_into`'s
    sliding-window maximum, generalized to effort units) - whichever qualifying activity or manual
    entry is the current window's best takes over the instant the old one ages out or is beaten,
    with no decay curve in between. Unlike enabled mode, manual entries compete in the same window
    as activities rather than unconditionally overriding it - there's no decay clock here for an
    unconditional override to usefully "reset"."""
    window: list[tuple[date, float, str, str | None]] = []
    if seed_anchor_value is not None:
        window.append((seed_anchor_date, _to_effort(field, seed_anchor_value), seed_anchor_kind, None))
    last_emitted_native = _round_native(field, _from_effort(field, window[0][1])) if window else None
    steps: list[Step] = []

    loop_start = min(start, seed_anchor_date) if seed_anchor_date is not None else start
    for d in _daterange(loop_start, end):
        manual = manual_by_day.get(d)
        if manual is not None:
            manual_effort = _to_effort(field, manual.value)
            window = [w for w in window if w[1] >= manual_effort]
            window.append((d, manual_effort, MANUAL, None))

        ref_effort = window[0][1] if window else None
        ref_native = _round_native(field, _from_effort(field, ref_effort)) if ref_effort is not None else None
        for candidate in candidates_by_day.get(d, []):
            if (
                not candidate.accepted
                and ref_native is not None
                and not _within_sanity_band(candidate.implied_value, ref_native, settings.sanity_pct)
            ):
                continue
            candidate_effort = _to_effort(field, candidate.implied_value)
            window = [w for w in window if w[1] >= candidate_effort]
            window.append((d, candidate_effort, MEASURED, candidate.activity_id))
            ref_native = _round_native(field, _from_effort(field, window[0][1]))

        cutoff = d - timedelta(days=settings.window_days)
        window = [w for w in window if w[0] > cutoff]

        if d >= start:
            if window:
                best_date, best_effort, best_kind, best_activity_id = window[0]
                new_native = _round_native(field, _from_effort(field, best_effort))
                if last_emitted_native is None or new_native != last_emitted_native:
                    steps.append(Step(new_native, best_kind, best_activity_id, best_date, d))
                    last_emitted_native = new_native
            else:
                last_emitted_native = None

    return steps
