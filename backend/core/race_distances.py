"""Standard race distances this app recognizes for bucket-based training-block-length
defaults (races.blocks.resolve_block_weeks). Lives in core, not races or accounts, since both
of those apps need it and accounts can't import from races (races.blocks already imports
accounts.models.User, so the reverse import would cycle).
"""

from typing import NamedTuple


class RaceDistanceBucket(NamedTuple):
    key: str
    label: str
    km: float | None  # None for "ultra" - the open-ended catch-all above marathon distance.


RACE_DISTANCE_BUCKETS: list[RaceDistanceBucket] = [
    RaceDistanceBucket("5k", "5K", 5.0),
    RaceDistanceBucket("10k", "10K", 10.0),
    RaceDistanceBucket("10mi", "10 Mile", 16.0934),
    RaceDistanceBucket("half", "Half Marathon", 21.0975),
    RaceDistanceBucket("marathon", "Marathon", 42.195),
    RaceDistanceBucket("ultra", "Ultra", None),
]

DEFAULT_RACE_BLOCK_WEEK_DEFAULTS: dict[str, int] = {
    "5k": 8,
    "10k": 10,
    "10mi": 12,
    "half": 12,
    "marathon": 16,
    "ultra": 20,
}


def default_race_block_week_defaults() -> dict[str, int]:
    """A real module-level function (not DEFAULT_RACE_BLOCK_WEEK_DEFAULTS.copy) so Django's
    migration writer can serialize it as a field default by import path."""
    return dict(DEFAULT_RACE_BLOCK_WEEK_DEFAULTS)
