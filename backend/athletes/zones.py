from collections.abc import Iterable
from typing import cast

from accounts.models import User

from .models import ZoneSet

ZONE_TYPES = ["heart_rate", "bike_power", "run_power", "pace"]

DEFAULT_ZONES = [
    {"name": "Z1 Recovery", "low_pct": 0, "high_pct": 55},
    {"name": "Z2 Endurance", "low_pct": 56, "high_pct": 75},
    {"name": "Z3 Tempo", "low_pct": 76, "high_pct": 90},
    {"name": "Z4 Threshold", "low_pct": 91, "high_pct": 105},
    {"name": "Z5 VO2max", "low_pct": 106, "high_pct": 150},
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


def reference_for(athlete: User, zone_type: str) -> int | None:
    """The threshold value a zone type's percentages are relative to, computed
    live from the athlete's profile rather than stored (it moves whenever the
    athlete's thresholds change).
    """
    field = THRESHOLD_FIELD_BY_ZONE_TYPE[zone_type]
    raw = getattr(athlete, field)
    return _mmss_to_seconds(raw) if zone_type == "pace" else cast("int | None", raw)


def get_or_create_zone_set(athlete: User, zone_type: str) -> ZoneSet:
    zone_set, _created = ZoneSet.objects.get_or_create(
        athlete=athlete, type=zone_type, defaults={"zones": DEFAULT_ZONES}
    )
    return zone_set


def zone_types_affected_by(changed_fields: Iterable[str]) -> list[str]:
    """Given the athlete profile fields that changed in an update, returns the
    zone types whose reference threshold depends on one of them.
    """
    return [zone_type for zone_type, field in THRESHOLD_FIELD_BY_ZONE_TYPE.items() if field in changed_fields]
