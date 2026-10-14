"""Activity sections - the course sections pipeline (courses.sections) applied to a run, walk or
ride's recorded altitude. Mirrors backend_java's ActivitySectionsService."""

from courses.sections import (
    ELEVATION_QUALITY_OK,
    SectionSettings,
    build_elevation_series,
    climbs_from_sections,
    profile_from_series,
    sections_from_series,
)

from .models import Activity, ActivityElevation

# Sports whose elevation is worth splitting into flat / climb / descent sections.
SECTION_SPORTS = ("run", "walk", "bike")


def _distance_altitude(activity: Activity) -> tuple[list[float], list[float | None]]:
    """The records' cumulative distance and altitude, skipping samples where distance didn't
    advance (paused) - only those two columns, never whole records."""
    rows = activity.records.filter(distance_km__isnull=False).order_by("t").values_list("distance_km", "altitude")
    distances: list[float] = []
    altitudes: list[float | None] = []
    last: float | None = None
    for distance_km, altitude in rows:
        if last is not None and distance_km <= last:
            continue
        distances.append(distance_km)
        altitudes.append(altitude)
        last = distance_km
    return distances, altitudes


def rebuild_elevation(activity: Activity) -> ActivityElevation:
    """(Re)builds and saves the activity's 10 m elevation series from its records."""
    quality, series = build_elevation_series(*_distance_altitude(activity))
    elevation, _ = ActivityElevation.objects.update_or_create(
        activity=activity, defaults={"elevation_quality": quality, "elevation_series": series}
    )
    return elevation


def ensure_elevation(activity: Activity) -> ActivityElevation:
    """The stored series, built on first read."""
    elevation = ActivityElevation.objects.filter(activity=activity).first()
    return elevation if elevation is not None else rebuild_elevation(activity)


def sections_payload(activity: Activity, elevation: ActivityElevation, settings: SectionSettings | None) -> dict:
    """The sections response, calculated now with the given settings or else the owner's."""
    effective = settings or activity.athlete.section_settings()
    ok = elevation.elevation_quality == ELEVATION_QUALITY_OK
    sections = sections_from_series(elevation.elevation_series, effective) if ok else []
    return {
        "object": "activity_sections",
        "activity_id": activity.id,
        "elevation_quality": elevation.elevation_quality,
        "elevation_profile": profile_from_series(elevation.elevation_series, effective) if ok else [],
        "sections": sections,
        "climbs": climbs_from_sections(sections),
    }
