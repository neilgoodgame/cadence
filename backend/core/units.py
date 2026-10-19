"""Unit parsing shared by MCP tools that take a human-typed distance (workouts/mcp.py's
create_workout, activities/mcp.py and races/mcp.py's custom splits) - mirrors backend_java's
McpUnits.
"""

import re
from typing import Any

from rest_framework.exceptions import ValidationError

_DISTANCE_PATTERN = re.compile(
    r"^\s*(?P<magnitude>\d+(?:\.\d+)?)\s*(?P<unit>m|km|mi|meters?|kilometers?|miles?)\s*$", re.IGNORECASE
)
_METERS_PER_KM = 1000.0
_METERS_PER_MILE = 1609.344


def parse_distance_meters(value: Any, field: str) -> int | None:
    """Requires an explicit unit rather than defaulting a bare number to metres - there's no
    valid interpretation of a bare number to silently accept."""
    if value is None or value == "":
        return None
    if not isinstance(value, str):
        raise ValidationError({field: f'{field} must be a string like "400m", "5km", or "3.1mi".'})
    match = _DISTANCE_PATTERN.match(value)
    if not match:
        raise ValidationError({field: f'{field} must be a number with a unit, e.g. "400m", "5km", or "3.1mi".'})
    magnitude = float(match.group("magnitude"))
    unit = match.group("unit").lower()
    if unit == "m" or unit.startswith("meter"):
        meters = magnitude
    elif unit == "km" or unit.startswith("kilomet"):
        meters = magnitude * _METERS_PER_KM
    else:
        meters = magnitude * _METERS_PER_MILE
    return round(meters)
