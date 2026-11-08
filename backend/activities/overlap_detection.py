"""Issue #498: runs once per newly-ingested activity (see uploads/processing.py) to detect and,
depending on the resolved overlap_handling mode, resolve same-ride-twice duplicates against the
athlete's existing activities. Mirrors backend_java's `ActivityOverlapDetectionService` - see its
docstring for the full design rationale (why detection reuses the overlap query's own defaults
rather than a separate ingest-time tolerance).

MERGE (issue #501 phase 4): consults merge_rule_service for the athlete's saved-or-built-in rule
for the pair's sport. No rule, or neither activity matches either side of the rule's device
patterns, falls back to "flagged" - there's nothing to safely auto-decide. Otherwise previews
the merge to get its alignment score; at or above MERGE_ALIGNMENT_THRESHOLD it applies for real
("merged"), hiding the source exactly like a manual merge-and-hide would; below it,
"merge_skipped_low_score" - detected and reported, nothing written, same as "flagged". The
threshold itself is a placeholder: issue #501's own "Open questions" leaves "what correlation
score allows automatic merging?" for calibration against real pair data, which hasn't happened
yet.
"""

from typing import TypedDict

from rest_framework.exceptions import ValidationError

from gear.mileage import apply_duplicate_change

from . import merge_rule_service
from .models import Activity
from .overlaps import find_overlaps

# run_merge is imported lazily inside _merge(), not here at module level - uploads.processing
# imports this module at the top level for detect_and_resolve_overlap, and merge_service (via
# merge_postprocess) imports back from uploads.processing, so a top-level import here would be
# circular. Same reasoning as athletes/threshold_history.py's own local import.

# Zwift + Garmin pairs are almost always exactly one real duplicate; this just bounds a
# pathological case (e.g. several short re-recorded attempts) from doing unbounded work.
MAX_PAIRS_PER_ACTIVITY = 10

# See the module docstring's MERGE paragraph - not yet calibrated against real data.
MERGE_ALIGNMENT_THRESHOLD = 0.9


class OverlapResolution(TypedDict):
    other_activity_id: str
    action: str  # "flagged" | "marked" | "merged" | "merge_skipped_low_score"
    primary_activity_id: str | None
    merge_id: str | None


def _is_multisport_linked(activity: Activity) -> bool:
    return activity.sport in ("multisport", "transition") or activity.parent_activity_id is not None


def _is_zwift(activity: Activity) -> bool:
    return bool(activity.device) and activity.device.lower().startswith("zwift")


def _sensor_channel_count(activity: Activity) -> int:
    return sum(
        1
        for value in (activity.avg_power, activity.avg_hr, activity.avg_cadence, activity.avg_core_temp)
        if value is not None
    )


def choose_primary(a: Activity, b: Activity) -> Activity:
    """Which of the two should survive as the primary - see the issue's own rule: the Zwift copy
    wins outright (it carries the route, virtual GPS track, distance/speed and elevation);
    failing that, the side with more populated sensor summary fields wins; failing that, the
    earlier start date, then the lower id, so the result is always deterministic rather than
    depending on which copy happened to import first."""
    a_zwift, b_zwift = _is_zwift(a), _is_zwift(b)
    if a_zwift != b_zwift:
        return a if a_zwift else b
    a_sensors, b_sensors = _sensor_channel_count(a), _sensor_channel_count(b)
    if a_sensors != b_sensors:
        return a if a_sensors > b_sensors else b
    if a.start_date != b.start_date:
        return a if a.start_date < b.start_date else b
    return a if a.pk <= b.pk else b


def _can_link(duplicate: Activity, primary: Activity) -> bool:
    """Mirrors activities.views._resolve_primary_activity's validation for the manual PATCH
    primary_activity_id path, now that choose_primary has settled which side plays which role.
    Deliberately asymmetric: `primary` having other duplicates already pointing to it is fine
    (the normal state for an established primary); only `duplicate` having duplicates of its
    own, or either side already being linked some other way, blocks it."""
    if duplicate.primary_activity_id is not None or primary.primary_activity_id is not None:
        return False
    if _is_multisport_linked(duplicate) or _is_multisport_linked(primary):
        return False
    return not duplicate.duplicate_activities.exists()


def _mark(activity: Activity, other_id: str) -> Activity | None:
    """Returns the primary if the pair was successfully linked, None if it couldn't be
    (see _can_link) or `other_id` no longer exists."""
    try:
        other = Activity.objects.get(pk=other_id)
    except Activity.DoesNotExist:
        return None
    primary = choose_primary(activity, other)
    duplicate = other if primary is activity else activity
    if not _can_link(duplicate, primary):
        return None
    duplicate.primary_activity = primary
    duplicate.save(update_fields=["primary_activity"])
    apply_duplicate_change(duplicate, was_duplicate=False, is_duplicate=True)
    return primary


def _merge(activity: Activity, other_id: str, apply: bool) -> OverlapResolution | None:
    """Returns a "merged"/"merge_skipped_low_score" resolution if merge_rule_service found an
    applicable rule and a device-pattern match for this pair; None if neither applies, in which
    case the caller falls back to "flagged". `apply=False` (the preview path) never writes
    anything regardless of score - it always previews."""
    from .merge_service import run_merge  # local import - see the module-level comment above.

    rule = merge_rule_service.resolve(activity.athlete_id, activity.sport)
    if rule is None:
        return None
    try:
        other = Activity.objects.get(pk=other_id)
    except Activity.DoesNotExist:
        return None
    assignment = merge_rule_service.assign(activity, other, rule)
    if assignment is None:
        return None
    target, source = assignment
    # Fast-fail on the one validation rule worth pre-checking here (same spirit as _mark's
    # _can_link) - still wrapped in the broader try/except below as a safety net for any other
    # validation rule, since this isn't meant to duplicate run_merge's full rule set, just avoid
    # a wasted preview computation for the common case.
    if _is_multisport_linked(target) or _is_multisport_linked(source):
        return None
    try:
        preview = run_merge(
            target_id=target.id,
            source_id=source.id,
            fields=rule.fields,
            null_policy=rule.null_policy,
            hide_source=True,
            preview=True,
        )
        if preview["alignment_score"] is None or preview["alignment_score"] < MERGE_ALIGNMENT_THRESHOLD:
            return {
                "other_activity_id": other_id,
                "action": "merge_skipped_low_score",
                "primary_activity_id": None,
                "merge_id": None,
            }
        if not apply:
            return {
                "other_activity_id": other_id,
                "action": "merged",
                "primary_activity_id": target.id,
                "merge_id": None,
            }
        result = run_merge(
            target_id=target.id,
            source_id=source.id,
            fields=rule.fields,
            null_policy=rule.null_policy,
            hide_source=True,
            preview=False,
        )
        return {
            "other_activity_id": other_id,
            "action": "merged",
            "primary_activity_id": target.id,
            "merge_id": result["merge_id"],
        }
    except ValidationError:
        # The rule's device patterns matched, but run_merge's own deeper checks (e.g. one side
        # already a duplicate of some third activity) refused it - same "can't safely do this
        # automatically, fall back to flagging" outcome as _mark's own _can_link failure, just
        # discovered one layer further in.
        return None


def resolve_pair(activity_id: str, other_activity_id: str, mode: str) -> OverlapResolution:
    """Resolves one already-found unresolved pair - shared by detect_and_resolve_overlap
    (anchored to one newly-ingested activity), the issue #498 backfill command
    (backfill_overlaps), and resolve_duplicates_batch, which instead walk every unresolved pair
    the overlap query returns for an athlete's whole history or a bounded batch respectively."""
    activity = Activity.objects.get(pk=activity_id)
    if mode == "mark":
        primary = _mark(activity, other_activity_id)
        if primary is not None:
            return {
                "other_activity_id": other_activity_id,
                "action": "marked",
                "primary_activity_id": primary.id,
                "merge_id": None,
            }
    if mode == "merge":
        merged = _merge(activity, other_activity_id, apply=True)
        if merged is not None:
            return merged
    # FLAG - also where MARK lands when the pair turned out not to be linkable (see _can_link),
    # and where MERGE lands when no rule/pattern match applies (see _merge).
    return {"other_activity_id": other_activity_id, "action": "flagged", "primary_activity_id": None, "merge_id": None}


def preview_resolution(activity_id: str, other_activity_id: str, mode: str) -> OverlapResolution:
    """Non-mutating counterpart to resolve_pair - the exact same choose_primary/_can_link (mark)
    or merge-rule/alignment (merge) decision, without writing anything. Used by
    resolve_duplicates_batch's dry_run mode."""
    activity = Activity.objects.get(pk=activity_id)
    other = Activity.objects.get(pk=other_activity_id)
    if mode == "mark":
        primary = choose_primary(activity, other)
        duplicate = other if primary is activity else activity
        if _can_link(duplicate, primary):
            return {
                "other_activity_id": other_activity_id,
                "action": "marked",
                "primary_activity_id": primary.id,
                "merge_id": None,
            }
    if mode == "merge":
        # apply=False - _merge's own preview=True call already has zero side effects, so this
        # never needs to go further even when the score would qualify for real.
        merged = _merge(activity, other_activity_id, apply=False)
        if merged is not None:
            return merged
    return {"other_activity_id": other_activity_id, "action": "flagged", "primary_activity_id": None, "merge_id": None}


def detect_and_resolve_overlap(activity: Activity, mode: str) -> list[OverlapResolution]:
    if mode == "off" or _is_multisport_linked(activity):
        return []

    page = find_overlaps(
        activity.athlete_id,
        activity_id=activity.id,
        limit=MAX_PAIRS_PER_ACTIVITY,
    )

    results: list[OverlapResolution] = []
    for pair in page["data"]:
        if pair["status"] != "unresolved":
            continue
        # activity_id pins the anchor to `activity` itself, so the other side is always `other`.
        results.append(resolve_pair(activity.id, pair["other"]["id"], mode))
    return results
