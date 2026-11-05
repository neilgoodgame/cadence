"""Issue #498: runs once per newly-ingested activity (see uploads/processing.py) to detect and,
depending on the resolved overlap_handling mode, resolve same-ride-twice duplicates against the
athlete's existing activities. Mirrors backend_java's `ActivityOverlapDetectionService` - see its
docstring for the full design rationale (why detection reuses the overlap query's own defaults
rather than a separate ingest-time tolerance, and why MERGE currently behaves like FLAG); keep
the two in sync when changing either.
"""

from typing import TypedDict

from gear.mileage import apply_duplicate_change

from .models import Activity
from .overlaps import find_overlaps

# Zwift + Garmin pairs are almost always exactly one real duplicate; this just bounds a
# pathological case (e.g. several short re-recorded attempts) from doing unbounded work.
MAX_PAIRS_PER_ACTIVITY = 10


class OverlapResolution(TypedDict):
    other_activity_id: str
    action: str  # "flagged" | "marked"


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


def _mark(activity: Activity, other_id: str) -> bool:
    try:
        other = Activity.objects.get(pk=other_id)
    except Activity.DoesNotExist:
        return False
    primary = choose_primary(activity, other)
    duplicate = other if primary is activity else activity
    if not _can_link(duplicate, primary):
        return False
    duplicate.primary_activity = primary
    duplicate.save(update_fields=["primary_activity"])
    apply_duplicate_change(duplicate, was_duplicate=False, is_duplicate=True)
    return True


def resolve_pair(activity_id: str, other_activity_id: str, mode: str) -> OverlapResolution:
    """Resolves one already-found unresolved pair - shared by detect_and_resolve_overlap
    (anchored to one newly-ingested activity) and the issue #498 backfill command
    (backfill_overlaps), which instead walks every unresolved pair the overlap query returns for
    an athlete's whole history."""
    activity = Activity.objects.get(pk=activity_id)
    if mode == "mark" and _mark(activity, other_activity_id):
        return {"other_activity_id": other_activity_id, "action": "marked"}
    # FLAG, or MERGE falling back to FLAG (see module docstring) - also where MARK lands when the
    # pair turned out not to be linkable (see _can_link).
    return {"other_activity_id": other_activity_id, "action": "flagged"}


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
