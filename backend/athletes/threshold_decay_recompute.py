"""Suffix resimulation: the design doc's "one mechanism" (docs/plans/threshold-decay.md section
4.2) for every threshold-decay trigger (ingest, manual edit, refresh, rebuild) - re-simulates a
field's ledger from `from_date` through `end` via threshold_decay.simulate(), replacing whatever
`measured`/`decayed` rows already cover that range. Only ever called for a decay-enabled athlete;
a decay-disabled one keeps going through threshold_history.py's existing
current_window_value/_recompute_and_record/replay_full_history_into entirely unchanged - see this
module's callers there.
"""

from __future__ import annotations

from dataclasses import dataclass, replace
from dataclasses import field as dataclass_field
from datetime import date, timedelta

from django.db import transaction

from accounts.models import User
from activities.models import Activity

from .decay_load import load_by_day_for_athlete
from .models import ThresholdHistory
from .threshold_decay import DecayCandidate, DecaySettings, Step, simulate
from .threshold_decay import ManualEntry as DecayManualEntry
from .threshold_history import (
    FIELD_SPORT,
    QualificationDiff,
    RerateDiff,
    _accepted_activity_ids,
    _batched_records_for,
    _chunked,
    _implied_value_detail,
    _latest_entry,
    _rerate_activities_from,
    _row_value,
    _seconds_to_mmss,
    _wire_value,
)


@dataclass(frozen=True)
class ResimulateResult:
    changed: bool
    rerated_count: int = 0
    would_rerate: list[RerateDiff] = dataclass_field(default_factory=list)
    would_requalify: list[QualificationDiff] = dataclass_field(default_factory=list)


def _decay_settings_for(athlete: User) -> DecaySettings:
    return DecaySettings(
        window_days=athlete.threshold_window_days,
        sanity_pct=athlete.threshold_sanity_pct,
        tau_days=athlete.threshold_decay_tau_days,
        min_pct=athlete.threshold_decay_min_pct,
        max_pct=athlete.threshold_decay_max_pct,
        enabled=athlete.threshold_decay_enabled,
    )


def _seed_v(athlete: User, field: str, from_date: date) -> float | None:
    """The ledger's current value as of `from_date - 1` - any kind, including decayed, but never
    a correction-only row: since #605 a correction is only ever a sanity-band reference, never the
    value (issue #607)."""
    row = (
        ThresholdHistory.objects.filter(
            athlete=athlete, field=field, is_correction_only=False, current_from__lt=from_date
        )
        .order_by("-current_from", "-id")
        .first()
    )
    return _row_value(field, row) if row is not None else None


def latest_non_decayed_row(athlete: User, field: str, before: date) -> ThresholdHistory | None:
    """The latest non-decayed, non-correction (measured or manual) row for (athlete, field) with
    effective_from < `before` - the anchor a suffix resimulation decays away from. A correction-only
    row is never an anchor (issue #607): since #605 it's only a sanity-band reference. Shared by
    _seed_anchor and athletes.tasks.apply_threshold_decay's daily-job gate so the anchor-selection
    rule lives in one place (issue #548 review) - a recent correction therefore doesn't hold the
    daily job off either."""
    return (
        ThresholdHistory.objects.filter(
            athlete=athlete, field=field, is_correction_only=False, effective_from__lt=before
        )
        .exclude(kind="decayed")
        .order_by("-effective_from", "-id")
        .first()
    )


def _seed_anchor(athlete: User, field: str, from_date: date) -> tuple[float | None, date | None, str | None]:
    """The latest non-decayed, non-correction row before `from_date` - the anchor `simulate` decays
    away from (see latest_non_decayed_row)."""
    row = latest_non_decayed_row(athlete, field, from_date)
    if row is None:
        return None, None, None
    return _row_value(field, row), row.effective_from, row.kind


def _gather_candidates(athlete: User, field: str, from_date: date, end: date) -> list[DecayCandidate]:
    """Every qualifying activity from `window_days` before `from_date` (to seed the successor
    pool) through `end` - same scan `current_window_value`/`_replay_full_history_into` already
    use, just reused here rather than re-implemented (issue #485 slice 3)."""
    window_start = from_date - timedelta(days=athlete.threshold_window_days)
    accepted_ids = _accepted_activity_ids(athlete, field)
    activities = list(
        Activity.objects.filter(
            athlete=athlete,
            sport=FIELD_SPORT[field],
            primary_activity__isnull=True,
            start_date__date__gte=window_start,
            start_date__date__lte=end,
        ).order_by("start_date")
    )
    candidates: list[DecayCandidate] = []
    for chunk in _chunked(activities):
        batched_records = _batched_records_for(chunk)
        for activity in chunk:
            detail = _implied_value_detail(activity, field, batched_records.get(activity.id, []))
            if detail is None:
                continue
            candidates.append(
                DecayCandidate(
                    date=activity.start_date.date(),
                    implied_value=detail.value,
                    activity_id=activity.id,
                    accepted=activity.id in accepted_ids,
                )
            )
    return candidates


def _gather_manual_entries(
    athlete: User, field: str, from_date: date, end: date, new_entry: DecayManualEntry | None
) -> list[DecayManualEntry]:
    """Every surviving (not correction-only - same exclusion `_replay_full_history_into`'s own
    manual-row merge already applies) manual ledger row in `[from_date, end]`, marked
    `already_recorded=True` so `simulate` respects its anchor-reset effect without duplicating it
    - plus the one brand-new entry a manual-edit trigger is itself recording, if any."""
    existing = ThresholdHistory.objects.filter(
        athlete=athlete,
        field=field,
        kind="manual",
        is_correction_only=False,
        effective_from__gte=from_date,
        effective_from__lte=end,
    )
    entries = [
        DecayManualEntry(date=row.effective_from, value=_row_value(field, row), already_recorded=True)
        for row in existing
    ]
    if new_entry is not None:
        entries.append(new_entry)
    return entries


def _gather_corrections(athlete: User, field: str, end: date) -> list[DecayManualEntry]:
    """Every correction-only manual row up to `end` (issue #605) - `simulate` uses them only as
    sanity-band references, never as anchors. Includes rows before `from_date` on purpose:
    the latest of those can still be the live reference when the resimulation starts."""
    rows = ThresholdHistory.objects.filter(
        athlete=athlete, field=field, kind="manual", is_correction_only=True, effective_from__lte=end
    ).order_by("effective_from", "id")
    return [DecayManualEntry(date=row.effective_from, value=_row_value(field, row)) for row in rows]


def _simulate_suffix(
    athlete: User,
    field: str,
    from_date: date,
    end: date,
    settings: DecaySettings,
    new_manual_entry: DecayManualEntry | None,
) -> list[Step]:
    """Gathers every input from the real ledger and runs `simulate` - the read-only half shared
    by resimulate_suffix, gather_preview_steps and simulate_suffix_steps, so they can never
    disagree about what a resimulation would produce."""
    seed_v = _seed_v(athlete, field, from_date)
    seed_anchor_value, seed_anchor_date, seed_anchor_kind = _seed_anchor(athlete, field, from_date)

    candidates = _gather_candidates(athlete, field, from_date, end)
    manual_entries = _gather_manual_entries(athlete, field, from_date, end, new_manual_entry)
    corrections = _gather_corrections(athlete, field, end)

    load_by_day = (
        load_by_day_for_athlete(
            athlete, field, seed_anchor_value, athlete.lthr, from_date - timedelta(days=settings.window_days), end
        )
        if seed_anchor_value
        else {}
    )

    return simulate(
        field,
        settings,
        seed_anchor_value,
        seed_anchor_date,
        seed_anchor_kind,
        candidates,
        manual_entries,
        load_by_day,
        from_date,
        end,
        seed_v_value=seed_v,
        corrections=corrections,
    )


def simulate_suffix_steps(athlete: User, field: str, from_date: date, end: date) -> list[Step]:
    """Read-only: the `Step`s a resimulation from `from_date` would write against the ledger as
    it stands right now, with the athlete's own settings. record_manual_value calls it either
    side of inserting a correction-only row to get a before/after pair for
    `step_qualification_diff` (issue #605)."""
    return _simulate_suffix(athlete, field, from_date, end, _decay_settings_for(athlete), None)


def step_qualification_diff(field: str, before: list[Step], after: list[Step]) -> list[QualificationDiff]:
    """The ledger slots (keyed by current_from) where two simulations of the same range pick a
    different measured winner. Comparing two simulations, rather than a simulation against the
    stored rows, attributes only what the one input that differs between them changes: stored
    rows can already disagree with a fresh simulation for reasons unrelated to the edit being
    previewed (e.g. correction rows saved before #605 made the decay-on path honour them), and
    those must not show up as this edit's consequences. `decayed`/`manual` steps are excluded
    from both sides - they never carry an activity_id, so they're not a "did a different ride
    qualify" event."""
    before_by_current_from = {s.current_from: s for s in before if s.kind == "measured"}
    after_by_current_from = {s.current_from: s for s in after if s.kind == "measured"}
    diffs = []
    for current_from in sorted(set(before_by_current_from) | set(after_by_current_from)):
        old_step = before_by_current_from.get(current_from)
        new_step = after_by_current_from.get(current_from)
        old_activity_id = old_step.activity_id if old_step else None
        new_activity_id = new_step.activity_id if new_step else None
        old_value = old_step.value if old_step else None
        new_value = new_step.value if new_step else None
        if old_activity_id != new_activity_id or old_value != new_value:
            diffs.append(
                QualificationDiff(
                    current_from=current_from,
                    old_activity_id=old_activity_id,
                    new_activity_id=new_activity_id,
                    old_value=_wire_value(field, old_value),
                    new_value=_wire_value(field, new_value),
                )
            )
    return diffs


def resimulate_suffix(
    athlete: User,
    field: str,
    from_date: date,
    end: date | None = None,
    new_manual_entry: DecayManualEntry | None = None,
    manual_set_by: User | None = None,
    rerate_activities: bool = True,
    preview: bool = False,
    collect_qualification_diff: bool = False,
) -> ResimulateResult:
    """Re-simulates `field`'s ledger from `from_date` through `end` (default today) and persists
    the result - the single operation behind every decay-enabled trigger (ingest, manual edit,
    refresh, rebuild; see threshold_history.py's own wiring). Deleting `measured`/`decayed` rows
    with `current_from >= from_date` then re-inserting `simulate`'s fresh `Step`s implements every
    row of the design doc's 4.2 table uniformly, including "append only" (refresh/the future daily
    job), since nothing exists at/after `from_date` yet in that case - the delete simply matches
    nothing.

    `new_manual_entry`/`manual_set_by` are for the manual-edit trigger only: the one brand-new
    entry being recorded becomes part of this same `simulate` call (via `ManualEntry.
    already_recorded=False`) rather than a separate direct write, and `manual_set_by` is attached
    to whichever resulting `Step` is that entry's own.

    `collect_qualification_diff` (issue #494, record_manual_value's own preview flag) diffs the
    `measured` `Step`s this call writes against a baseline simulation of the same range without
    `new_manual_entry` - see step_qualification_diff for why a baseline simulation rather than
    the stored rows (issue #605 review).

    Runs inside a transaction holding a row lock on `athlete` for its duration (design doc 4.3) -
    guards against two triggers (e.g. a future daily job and an ingest) re-simulating the same
    field at once."""
    end = end or date.today()
    settings = _decay_settings_for(athlete)

    with transaction.atomic():
        User.objects.select_for_update().get(pk=athlete.id)

        # Baseline: the same range simulated without the new entry (see step_qualification_diff
        # for why this, not the stored rows).
        baseline_steps = (
            _simulate_suffix(athlete, field, from_date, end, settings, None) if collect_qualification_diff else []
        )

        steps = _simulate_suffix(athlete, field, from_date, end, settings, new_manual_entry)

        would_requalify = step_qualification_diff(field, baseline_steps, steps) if collect_qualification_diff else []

        ThresholdHistory.objects.filter(
            athlete=athlete, field=field, current_from__gte=from_date, kind__in=["measured", "decayed"]
        ).delete()

        rows = []
        for step in steps:
            if field == "threshold_pace":
                value_numeric, value_pace = None, _seconds_to_mmss(step.value)
            else:
                value_numeric, value_pace = round(step.value), ""
            set_by = None
            if step.kind == "manual" and new_manual_entry is not None and step.current_from == new_manual_entry.date:
                set_by = manual_set_by
            rows.append(
                ThresholdHistory(
                    athlete=athlete,
                    field=field,
                    value_numeric=value_numeric,
                    value_pace=value_pace,
                    source_activity_id=step.activity_id,
                    set_by=set_by,
                    effective_from=step.effective_from,
                    current_from=step.current_from,
                    kind=step.kind,
                )
            )
        ThresholdHistory.objects.bulk_create(rows)

        changed = bool(steps)
        rerated_count, would_rerate = 0, []
        if changed:
            current = _latest_entry(athlete, field)
            live_value = (
                current.value_pace
                if (current and field == "threshold_pace")
                else (current.value_numeric if current else None)
            )
            setattr(athlete, field, live_value)
            athlete.save(update_fields=[field])

            # Load-bearing, not cosmetic (design doc 4.6) - without this, stored TSS/IF silently
            # drift from the ledger the moment a retroactive change happens (the exact failure
            # mode 4.4 calls out: "compute-on-read doesn't fit... TSS would still be built from
            # the stale value"). `rerate_activities`/`preview` only matter for the manual-edit
            # trigger (record_manual_value's own API-level flags); every other trigger leaves
            # them at the defaults (always persist, never bother collecting an unused diff).
            rerated_count, would_rerate = _rerate_activities_from(
                athlete, field, from_date, persist=rerate_activities, collect_diff=preview
            )

    return ResimulateResult(
        changed=changed, rerated_count=rerated_count, would_rerate=would_rerate, would_requalify=would_requalify
    )


def gather_preview_steps(
    athlete: User,
    field: str,
    from_date: date,
    end: date,
    tau_days: float | None = None,
    min_pct: float | None = None,
    max_pct: float | None = None,
) -> tuple[list[Step], DecaySettings]:
    """Issue #487: `resimulate_suffix`'s own read-only half, stopped before its write block (no
    `ThresholdHistory` delete/bulk_create, no `athlete.save()`, no re-rating) - gathers the exact
    same inputs and calls `simulate`, but never touches the ledger, so a caller can try different
    `tau_days`/`min_pct`/`max_pct` against real history with zero side effects. Returns the
    `Step`s alongside the `DecaySettings` actually used, so a caller overriding only one of the
    three can see what the other two defaulted to (the athlete's own saved settings).

    `enabled` is forced `True` regardless of `athlete.threshold_decay_enabled` - `simulate` takes
    a different, legacy ("disabled") branch when `enabled=False` that ignores `tau_days`/
    `min_pct`/`max_pct` entirely, which would silently make every override here a no-op for an
    athlete who hasn't turned decay on yet. `window_days`/`sanity_pct` are never overridden - the
    issue's own signature doesn't offer that, and `_gather_candidates`'s own window scan already
    reads `athlete.threshold_window_days` directly, so overriding `settings.window_days` alone
    wouldn't even change what candidates are gathered."""
    base_settings = _decay_settings_for(athlete)
    settings = replace(
        base_settings,
        tau_days=tau_days if tau_days is not None else base_settings.tau_days,
        min_pct=min_pct if min_pct is not None else base_settings.min_pct,
        max_pct=max_pct if max_pct is not None else base_settings.max_pct,
        enabled=True,
    )
    return _simulate_suffix(athlete, field, from_date, end, settings, None), settings
