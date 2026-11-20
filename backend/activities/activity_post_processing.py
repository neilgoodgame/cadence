"""Issue #525: the one real implementation of "recompute everything downstream of a changed
Record stream" - derived stats, decoupling/durability, duration curve, best efforts, the
threshold ledger/TSS cascade, and workout matching - that both the ingest pipeline
(uploads.processing._ingest_activity) and merge/unmerge run through, replacing the interim,
merge-only module (merge_postprocess.py) issue #501 added while ingest was deliberately left
untouched. Mirrors backend_java's ActivityPostProcessingService - keep the two in sync when
changing either.
"""

from collections.abc import Iterable

from athletes import threshold_suggestions
from athletes.threshold_history import FIELD_SPORT, recompute_for_activity
from uploads.processing import (
    _write_duration_curves,
    attempt_workout_match,
    backfill_extended_stats,
    compute_decoupling_and_durability_for_activity,
    recompute_after_duplicate_change,
    update_best_efforts,
)

from .models import Activity

# A multisport parent's stream mixes sports (no fair curve/best-effort), same exclusion
# uploads.processing's own recompute candidate queries already apply.
_CURVE_AND_BEST_EFFORT_EXCLUDED_SPORTS = ("multisport", "transition")


def threshold_fields_for_sports(*sports: str) -> list[str]:
    """The threshold fields activities of any of ``sports`` can define (bike: ftp; run:
    critical_run_power and threshold_pace; every other sport: none)."""
    return [field for field, field_sport in FIELD_SPORT.items() if field_sport in sports]


def recompute_power_hr_and_decoupling(target_id: str, avg_left_balance_pct: float | None = None) -> None:
    """Avg/norm/max power, avg/max HR, cadence, elevation, calories, TRIMP, run air temp/
    humidity, intensity+tss, plus (bike/run only) decoupling/durability - all re-derived from
    the activity's own stored Record rows (backfill_extended_stats). ``avg_left_balance_pct`` is
    the one field that can never be recovered this way - see backfill_extended_stats' own
    docstring - so only a caller with a fresh parse (ingest) supplies it; every other caller
    omits it and the field is left untouched."""
    activity = Activity.objects.get(pk=target_id)
    athlete = activity.athlete
    update_fields = backfill_extended_stats(activity, athlete, avg_left_balance_pct)
    if update_fields:
        activity.save(update_fields=update_fields)
    if activity.sport in ("bike", "run"):
        compute_decoupling_and_durability_for_activity(activity, athlete)


def recompute_duration_curve_and_best_efforts(target_id: str) -> None:
    """Skipped for a multisport parent/transition leg - no fair curve/best-effort across a
    mixed-sport stream, same exclusion ingest itself applies."""
    activity = Activity.objects.get(pk=target_id)
    if activity.sport in _CURVE_AND_BEST_EFFORT_EXCLUDED_SPORTS:
        return
    athlete = activity.athlete
    records = list(activity.records.order_by("t"))
    if not records:
        return
    power_series = [r.power for r in records]
    hr_series = [r.heartrate for r in records]
    t_series = [r.t for r in records]
    distance_series = [r.distance_km for r in records]
    _write_duration_curves(activity, power_series, hr_series)
    update_best_efforts(activity, athlete, power_series, t_series, distance_series, hr_series)


def recompute_threshold_for_new_activity(activity: Activity) -> None:
    """Ingest-only: checks whether this brand-new activity's own effort raises or lowers the
    athlete's current threshold window, so process_new_activity can rate this same activity's
    own TSS/intensity against the (possibly just-updated) result - see recompute_for_activity's
    own docstring. Merge/backfill rely on the athlete-wide cascade in recompute_after_merge
    instead: an *existing* activity's rewritten stream can retroactively change who held a
    threshold on any day since its own date, which this single-activity check doesn't attempt."""
    recompute_for_activity(activity)
    # A new bike/run activity can change the threshold suggestions list even when it didn't
    # change the current threshold itself - e.g. it's now the "near match" that suppresses an
    # upcoming_drop warning. Invalidated unconditionally for bike/run, not just on an actual
    # threshold change.
    if activity.sport in ("bike", "run"):
        threshold_suggestions.invalidate(activity.athlete_id)


def process_new_activity(target_id: str, avg_left_balance_pct: float | None = None) -> None:
    """Call once, right after a freshly-parsed activity's Record/Lap rows have been bulk-created
    - the real ingest-time sequence uploads.processing._ingest_activity used to run inline. The
    threshold check runs first, before anything that could depend on it - not just TSS/
    decoupling (recompute_power_hr_and_decoupling, last, needs the freshly-set threshold for
    both), but also update_best_efforts' own running_power kind, which only fires when
    athlete.critical_run_power is already truthy: an athlete's very first qualifying run must
    bootstrap that value via this same threshold check before its own best effort can be
    recorded, or it's silently skipped (confirmed broken when this ran after duration-curve/
    best-efforts, matching an equivalent bug this fixed in backend_java - see
    RunningPowerSourceIngestionTest there). Called unconditionally, including for a multisport
    parent: every piece here is either correctly sport-gated internally, a provable no-op for
    sport "multisport" (no ScheduledWorkout or ThresholdHistory field is ever defined for it),
    or self-healing (the parent's own tss is immediately overwritten as the sum of its legs',
    right after this returns, in ingest_upload)."""
    activity = Activity.objects.get(pk=target_id)
    athlete = activity.athlete
    recompute_threshold_for_new_activity(activity)
    recompute_duration_curve_and_best_efforts(target_id)
    attempt_workout_match(activity, athlete)
    recompute_power_hr_and_decoupling(target_id, avg_left_balance_pct)


def recompute_after_merge(target_id: str, threshold_fields: Iterable[str] | None = None) -> None:
    """Call after merge_apply.apply_merge_fields has committed - never on a preview. ``target_id``
    keeps its own id across a merge, so this always re-derives the target, never the (now-
    exhausted) source.

    ``threshold_fields`` scopes the athlete-wide cascade to just those fields (None = all three;
    empty = skip the cascade, including the TSS re-rate). A merge only rewrites the target's own
    streams, so a caller that knows the sports involved can pass only what they can define."""
    recompute_activity_level_only(target_id)
    activity = Activity.objects.get(pk=target_id)

    # Issue #498's cascade: this activity's own data just changed underneath it, so the threshold
    # ledger and every activity's TSS/intensity need the same athlete-wide rebuild a duplicate
    # mark/unmark/set_primary already triggers. Issue #545: from this activity's own date - the
    # merge only rewrote its streams, so nothing earlier in the ledger can have changed.
    recompute_after_duplicate_change(activity.athlete, threshold_fields, from_date=activity.start_date.date())

    attempt_workout_match(activity, activity.athlete)


def recompute_activity_level_only(target_id: str) -> None:
    """Issue #503 (phase 2): everything recompute_after_merge does except the athlete-wide
    cascade and the workout-match attempt. A bulk caller re-designating many activities for one
    athlete (e.g. athletes.tasks.recompute_running_power_source) calls this per activity, then
    recompute_after_duplicate_change once at the end, instead of once per activity - the cascade
    is a full athlete-wide rebuild, so calling it per activity would be quadratic in the athlete's
    activity count. The workout-match attempt is skipped entirely by such a caller, not just
    deferred - re-designating a power source never changes an activity's duration/distance/
    structure, so there's nothing for it to re-match."""
    recompute_power_hr_and_decoupling(target_id)
    recompute_duration_curve_and_best_efforts(target_id)
