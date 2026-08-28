from collections.abc import Iterable
from typing import TYPE_CHECKING, cast

from accounts.models import User

from .models import ThresholdHistory, ZoneSet

if TYPE_CHECKING:
    from activities.models import Activity

ZONE_TYPES = ["heart_rate", "bike_power", "run_power", "pace"]

DEFAULT_ZONES = [
    {"name": "Z1 Recovery", "low_pct": 0, "high_pct": 55},
    {"name": "Z2 Endurance", "low_pct": 56, "high_pct": 75},
    {"name": "Z3 Tempo", "low_pct": 76, "high_pct": 90},
    {"name": "Z4 Threshold", "low_pct": 91, "high_pct": 105},
    {"name": "Z5 VO2max", "low_pct": 106, "high_pct": 150},
]

# Jack Daniels' five training paces (Daniels' Running Formula), not the generic power/HR table
# above - reusing that table for pace via zone_range's reciprocal transform (see the frontend's
# lib/zones.ts) stretches disproportionately at the easy end, since inverting a % is nonlinear.
# Percentages here are %-of-threshold-pace *effort* (matching every other zone type's "higher %
# = harder" convention - a real pace comes from reference / (pct / 100)), calibrated from
# Daniels' published VDOT tables so a runner's own Threshold pace lands inside the Threshold
# band, not at one of its edges. Daniels' own Threshold pace is itself defined as roughly a
# 60-minute effort - the same window this app's own threshold_pace/critical_run_power already
# use, so the mapping is a natural fit, not an approximation layered on top of an unrelated
# definition.
DEFAULT_PACE_ZONES = [
    {"name": "Easy", "low_pct": 0, "high_pct": 83},
    {"name": "Marathon", "low_pct": 84, "high_pct": 93},
    {"name": "Threshold", "low_pct": 94, "high_pct": 101},
    {"name": "Interval", "low_pct": 102, "high_pct": 109},
    {"name": "Repetition", "low_pct": 110, "high_pct": 150},
]

# Which athlete profile field a zone type's percentages are relative to.
THRESHOLD_FIELD_BY_ZONE_TYPE = {
    "heart_rate": "lthr",
    "bike_power": "ftp",
    "run_power": "critical_run_power",
    "pace": "threshold_pace",
}


def _mmss_to_seconds(value: str | None) -> int | None:
    """ "mm:ss" per km -> total seconds. Returns None if unset or malformed.

    A local parser rather than core.cql.parse_t, so this app doesn't depend
    on the query-language package for an unrelated mm:ss format.
    """
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


def reference_for(athlete: User, zone_type: str, activity: "Activity | None" = None) -> int | None:
    """The threshold value a zone type's percentages are relative to.

    Without `activity`, this is computed live from the athlete's *current* profile - the
    original behavior, still used for the athlete-level Zone Editor and the HR-based TSS/TRIMP
    helpers (heart_rate has no per-activity history to fall back to - lthr isn't rolling-window
    derived). With `activity`, bike_power/run_power/pace instead look up the ThresholdHistory
    entry that was actually the *recorded current value* at that activity's own date - filtered
    on current_from, not effective_from. Those two dates differ whenever a row only became
    current later than its own qualifying activity's date (an earlier, better entry aging out of
    the window - see ThresholdHistory.current_from's docstring): filtering on effective_from
    would let such a row match its own activity's date every time (effective_from <= that same
    date trivially holds), even though the row wasn't actually in effect yet. This keeps a
    historic ride's zones pinned to what was genuinely true when it happened, rather than to a
    later value that happens to share a source activity in the same neighborhood.
    """
    field = THRESHOLD_FIELD_BY_ZONE_TYPE[zone_type]
    if activity is not None and zone_type != "heart_rate":
        entry = (
            ThresholdHistory.objects.filter(athlete=athlete, field=field, current_from__lte=activity.start_date.date())
            .order_by("-current_from")
            .first()
        )
        if entry is None:
            return None
        return _mmss_to_seconds(entry.value_pace) if zone_type == "pace" else entry.value_numeric
    raw = getattr(athlete, field)
    return _mmss_to_seconds(raw) if zone_type == "pace" else cast("int | None", raw)


def get_or_create_zone_set(athlete: User, zone_type: str) -> ZoneSet:
    defaults = DEFAULT_PACE_ZONES if zone_type == "pace" else DEFAULT_ZONES
    zone_set, _created = ZoneSet.objects.get_or_create(athlete=athlete, type=zone_type, defaults={"zones": defaults})
    return zone_set


def zone_types_affected_by(changed_fields: Iterable[str]) -> list[str]:
    """Given the athlete profile fields that changed in an update, returns the
    zone types whose reference threshold depends on one of them.
    """
    return [zone_type for zone_type, field in THRESHOLD_FIELD_BY_ZONE_TYPE.items() if field in changed_fields]
