"""Issue #501 (phase 1, part 3): re-derives everything downstream of a merge's rewritten Record
stream (see ``merge_apply.apply_merge_fields``) - derived stats, decoupling/durability, duration
curve, best efforts, the threshold ledger/TSS cascade, and workout matching.

Hand-composes the existing per-activity/per-athlete recompute utilities already used for
backfills and the issue #498 duplicate-mark cascade, rather than a genuine shared
ActivityPostProcessingService the real ingest pipeline (``uploads.processing._ingest_activity``)
also runs through - that refactor is tracked separately (issue #525) since it touches live,
already-deployed ingest code. This module is the interim, lower-risk composition and should be
deleted once #525 lands. Mirrors backend_java's ActivityMergePostProcessingService - keep the two
in sync when changing either.
"""

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


def recompute_after_merge(target_id: str) -> None:
    """Call after merge_apply.apply_merge_fields has committed - never on a preview. ``target_id``
    keeps its own id across a merge, so this always re-derives the target, never the (now-
    exhausted) source."""
    recompute_activity_level_only(target_id)
    activity = Activity.objects.get(pk=target_id)

    # Issue #498's cascade: this activity's own data just changed underneath it, so the threshold
    # ledger and every activity's TSS/intensity need the same full-athlete rebuild a duplicate
    # mark/unmark/set_primary already triggers.
    recompute_after_duplicate_change(activity.athlete)

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
    activity = Activity.objects.get(pk=target_id)
    athlete = activity.athlete

    # Avg/norm/max power, cadence, elevation, calories, TRIMP, run air temp/humidity, plus
    # (bike/run only) decoupling/durability - all from the now-rewritten Record rows.
    update_fields = backfill_extended_stats(activity, athlete)
    if update_fields:
        activity.save(update_fields=update_fields)
    if activity.sport in ("bike", "run"):
        compute_decoupling_and_durability_for_activity(activity, athlete)

    if activity.sport not in _CURVE_AND_BEST_EFFORT_EXCLUDED_SPORTS:
        records = list(activity.records.order_by("t"))
        if records:
            power_series = [r.power for r in records]
            hr_series = [r.heartrate for r in records]
            t_series = [r.t for r in records]
            distance_series = [r.distance_km for r in records]
            _write_duration_curves(activity, power_series, hr_series)
            update_best_efforts(activity, athlete, power_series, t_series, distance_series, hr_series)
