"""Issue #540 (phase 5): agreement trends over time - per-activity agreement statistics
(bias/MAE/RMSE/Pearson r/tolerance/lag/dropouts) between two device-class tags for one field,
across an athlete's activities in a date range. A distinct orchestration (iterate activities,
skip/aggregate) over activity_source_service's per-activity building blocks - see that module
for _resolve_tagged_series and stream_comparison for the pure statistics. Mirrors
backend_java's AgreementTrendService.java - keep the two in sync when changing either.

Per-activity cost is non-trivial (each activity's full Record set or ActivityStream values
array is loaded, then comparison + lag (+ dropout) math runs over it) - the same shape of
acceptable cost core/derived.py's _daily_above_z2_load already accepts for a similar
per-activity aggregation: "acceptable for a calibration tool queried occasionally over a
bounded window, not a high-frequency dashboard path - revisit if that ever changes."
"""

import dataclasses
from dataclasses import dataclass
from datetime import date

from rest_framework.exceptions import ValidationError

from . import stream_comparison
from .activity_source_service import (
    VALID_FIELDS,
    _apply_power_dropout_exclusion,
    _resolve_tagged_series,
)
from .models import Activity

# Device-class tag vocabulary per field - the tags device_a/device_b are validated against.
# power's is sport-dependent (bike vs run), which is why field="power" requires a sport:
# there's no sensible single vocabulary otherwise, and mixing bike/run power into one trend is
# meaningless anyway.
DEVICE_TAG_VOCABULARY: dict[str, tuple[str, ...] | dict[str, tuple[str, ...]]] = {
    "power": {"bike": ("trainer", "pedals"), "run": ("native", "stryd")},
    "heartrate": ("strap", "wrist"),
    "cadence": ("pedals", "trainer", "watch"),
}

# The real training sports an optional sport filter may name for heartrate/cadence - same list
# athletes/mcp.py's get_fitness_trend validates against.
_VALID_FILTER_SPORTS = ("bike", "run", "swim", "walk", "row")


@dataclass(frozen=True)
class AgreementTrendPoint:
    activity_id: str
    name: str
    date: date
    overlap_seconds: int
    bias: float
    mae: float
    rmse: float
    pearson_r: float
    within_tolerance_pct: float
    lag_seconds: int | None
    dropout_seconds_a: int = 0
    dropout_seconds_b: int = 0


def _validate_device_tags(field: str, device_a: str, device_b: str, sport: str | None) -> None:
    """field reuses VALID_FIELDS' own check at the caller; this validates device_a/device_b
    against field's vocabulary (sport-dependent for power) and the sport param itself. Only a
    bad combination raises - "not enough data on this one activity" is never an error here,
    that activity is just skipped and tallied."""
    if field == "power":
        if not sport or sport not in ("bike", "run"):
            raise ValidationError({"sport": "sport is required when field is 'power' (must be 'bike' or 'run')."})
        vocabulary = DEVICE_TAG_VOCABULARY["power"][sport]
    else:
        if sport is not None and sport not in _VALID_FILTER_SPORTS:
            raise ValidationError({"sport": "sport must be one of bike, run, swim, walk, row."})
        vocabulary = DEVICE_TAG_VOCABULARY[field]
    for param, tag in (("device_a", device_a), ("device_b", device_b)):
        if tag not in vocabulary:
            raise ValidationError({param: f"{param} must be one of {', '.join(vocabulary)} for field '{field}'."})
    if device_a == device_b:
        raise ValidationError({"device_b": "device_a and device_b must be two different device tags."})


def compute_agreement_trend(
    athlete_id: str,
    field: str,
    device_a: str,
    device_b: str,
    sport: str | None,
    from_date: date,
    to_date: date,
    exclude_dropouts: bool = True,
    include_duplicates: bool = False,
) -> tuple[list[AgreementTrendPoint], dict[str, int]]:
    """Issue #540 (phase 5). Iterates the athlete's activities in [from_date, to_date]
    (Activity.sport filter applied if given; multisport children and - unless include_duplicates,
    mirroring _daily_above_z2_load's own flag - same-ride-twice duplicate copies excluded, the
    same exclusion every other multi-activity aggregation in this codebase makes), resolves
    device_a/device_b each to a series via _resolve_tagged_series, and reuses
    stream_comparison.compare (same tolerance rule compare_streams uses) plus find_lag and - for
    field="power" - the same dropout detection compare_streams runs, per activity. Returns the
    points plus a skip-reason tally. Never raises for "not enough data on this one activity" -
    that activity is just skipped and counted; only a bad field/device_a/device_b/sport
    combination is a 400."""
    if field not in VALID_FIELDS:
        raise ValidationError({"field": "field must be 'power', 'heartrate', or 'cadence'."})
    _validate_device_tags(field, device_a, device_b, sport)

    qs = Activity.objects.filter(
        athlete_id=athlete_id, start_date__date__range=(from_date, to_date), parent_activity__isnull=True
    )
    if sport:
        qs = qs.filter(sport=sport)
    if not include_duplicates:
        qs = qs.filter(primary_activity__isnull=True)
    # sources/streams prefetched so _resolve_tagged_series looks them up in memory instead of
    # calling _load_field_series (a get_object_or_404 per source) - see its own docstring.
    qs = qs.order_by("start_date").prefetch_related("sources__streams")

    tolerance = (
        stream_comparison.Tolerance.of_absolute(5.0)
        if field == "heartrate"
        else stream_comparison.Tolerance.of_relative_pct(3.0)
    )

    points: list[AgreementTrendPoint] = []
    skipped = {"no_device_a": 0, "no_device_b": 0, "insufficient_overlap": 0}

    for activity in qs:
        series_a = _resolve_tagged_series(activity, field, device_a)
        if series_a is None:
            skipped["no_device_a"] += 1
            continue
        series_b = _resolve_tagged_series(activity, field, device_b)
        if series_b is None:
            skipped["no_device_b"] += 1
            continue

        compare_series_a, compare_series_b = series_a, series_b
        dropout_seconds_a = 0
        dropout_seconds_b = 0
        if field == "power":
            exclusion = _apply_power_dropout_exclusion(activity, series_a, series_b, exclude_dropouts)
            compare_series_a, compare_series_b = exclusion.compare_series_a, exclusion.compare_series_b
            dropout_seconds_a, dropout_seconds_b = len(exclusion.dropout_seconds_a), len(exclusion.dropout_seconds_b)

        comparison = stream_comparison.compare(compare_series_a, compare_series_b, tolerance)
        if comparison is None:
            skipped["insufficient_overlap"] += 1
            continue
        lag = stream_comparison.find_lag(compare_series_a, compare_series_b)

        points.append(
            AgreementTrendPoint(
                activity_id=activity.id,
                name=activity.name,
                date=activity.start_date.date(),
                overlap_seconds=comparison.overlap_seconds,
                bias=comparison.bias,
                mae=comparison.mae,
                rmse=comparison.rmse,
                pearson_r=comparison.pearson_r,
                within_tolerance_pct=comparison.within_tolerance_pct,
                lag_seconds=lag.lag_seconds if lag is not None else None,
                dropout_seconds_a=dropout_seconds_a,
                dropout_seconds_b=dropout_seconds_b,
            )
        )

    return points, skipped


def agreement_trend_response(
    field: str,
    device_a: str,
    device_b: str,
    sport: str | None,
    from_date: date,
    to_date: date,
    points: list[AgreementTrendPoint],
    skipped: dict[str, int],
) -> dict:
    """JSON-serializable shape of a compute_agreement_trend result - shared by the REST view and
    the MCP tool (dataclasses.asdict alone would leave each point's date as a datetime.date)."""
    return {
        "field": field,
        "device_a": device_a,
        "device_b": device_b,
        "sport": sport,
        "from_date": from_date.isoformat(),
        "to_date": to_date.isoformat(),
        "points": [{**dataclasses.asdict(point), "date": point.date.isoformat()} for point in points],
        "skipped": skipped,
    }
