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
    _qualification_diff,
    _rerate_activities_from,
    _row_value,
    _seconds_to_mmss,
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
    """The ledger's current value as of `from_date - 1` - any kind, including decayed."""
    row = (
        ThresholdHistory.objects.filter(athlete=athlete, field=field, current_from__lt=from_date)
        .order_by("-current_from", "-id")
        .first()
    )
    return _row_value(field, row) if row is not None else None


def latest_non_decayed_row(athlete: User, field: str, before: date) -> ThresholdHistory | None:
    """The latest non-decayed (measured or manual) row for (athlete, field) with effective_from <
    `before` - the anchor a suffix resimulation decays away from. Shared by _seed_anchor and
    athletes.tasks.apply_threshold_decay's daily-job gate so the anchor-selection rule lives in one
    place (issue #548 review)."""
    return (
        ThresholdHistory.objects.filter(athlete=athlete, field=field, effective_from__lt=before)
        .exclude(kind="decayed")
        .order_by("-effective_from", "-id")
        .first()
    )


def _seed_anchor(athlete: User, field: str, from_date: date) -> tuple[float | None, date | None, str | None]:
    """The latest non-decayed row before `from_date` - the anchor `simulate` decays away from."""
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
    `measured` rows this call is about to delete against the `measured` `Step`s it's about to
    write in their place - same shape as threshold_history._qualification_diff's decay-off use,
    deliberately excluding `decayed`/`manual` steps from both sides: those never carry an
    activity_id, so they're not a "did a different ride qualify" event, the same way a manual
    winner's own row is already excluded from would_rerate's diff.

    Runs inside a transaction holding a row lock on `athlete` for its duration (design doc 4.3) -
    guards against two triggers (e.g. a future daily job and an ingest) re-simulating the same
    field at once."""
    end = end or date.today()
    settings = _decay_settings_for(athlete)

    with transaction.atomic():
        User.objects.select_for_update().get(pk=athlete.id)

        old_measured_rows = (
            list(
                ThresholdHistory.objects.filter(
                    athlete=athlete, field=field, kind="measured", current_from__gte=from_date
                )
            )
            if collect_qualification_diff
            else []
        )

        seed_v = _seed_v(athlete, field, from_date)
        seed_anchor_value, seed_anchor_date, seed_anchor_kind = _seed_anchor(athlete, field, from_date)

        candidates = _gather_candidates(athlete, field, from_date, end)
        manual_entries = _gather_manual_entries(athlete, field, from_date, end, new_manual_entry)

        load_by_day = (
            load_by_day_for_athlete(
                athlete, field, seed_anchor_value, athlete.lthr, from_date - timedelta(days=settings.window_days), end
            )
            if seed_anchor_value
            else {}
        )

        steps = simulate(
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
        )

        would_requalify = (
            _qualification_diff(field, old_measured_rows, [step for step in steps if step.kind == "measured"])
            if collect_qualification_diff
            else []
        )

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

    seed_v = _seed_v(athlete, field, from_date)
    seed_anchor_value, seed_anchor_date, seed_anchor_kind = _seed_anchor(athlete, field, from_date)

    candidates = _gather_candidates(athlete, field, from_date, end)
    manual_entries = _gather_manual_entries(athlete, field, from_date, end, None)

    load_by_day = (
        load_by_day_for_athlete(
            athlete, field, seed_anchor_value, athlete.lthr, from_date - timedelta(days=settings.window_days), end
        )
        if seed_anchor_value
        else {}
    )

    steps = simulate(
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
    )
    return steps, settings
