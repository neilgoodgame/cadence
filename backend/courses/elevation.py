"""Building and reading a Course's stored elevation series - shared by the course views, the
MCP tools, data export and the recompute endpoints. Mirrors backend_java's
CourseService.rebuildElevationSeries / ensureElevationSeries / sectionsFor."""

from django.core.files.storage import default_storage

from . import processing
from .models import Course
from .sections import (
    ELEVATION_QUALITY_MISSING,
    ELEVATION_QUALITY_OK,
    SectionSettings,
    build_elevation_series,
    climbs_from_sections,
    sections_from_series,
)


def _source_track(course: Course) -> list[processing.TrackPoint] | None:
    """The course's original track - its uploaded GPX if the file still exists, else its
    source activity's GPS track - or None when neither is available any more."""
    if course.gpx_file and default_storage.exists(course.gpx_file):
        with default_storage.open(course.gpx_file, "rb") as f:
            return processing.parse_gpx_track(f.read())
    if course.source_activity is not None:
        return processing.track_points_from_activity(course.source_activity)
    return None


def rebuild_elevation_series(course: Course) -> bool:
    """Rebuilds elevation_series/elevation_quality from the course's source data and saves
    them. False (nothing changed) when the source GPX file and activity are both gone, or
    the source no longer parses."""
    try:
        points = _source_track(course)
    except processing.GpxProcessingError:
        return False
    if points is None:
        return False
    quality, series = build_elevation_series([p.distance_km for p in points], [p.elevation for p in points])
    course.elevation_quality = quality
    course.elevation_series = series
    course.save(update_fields=["elevation_quality", "elevation_series"])
    return True


def ensure_elevation_series(course: Course) -> None:
    """Fills in the series on first read for a course created before sections existed
    (elevation_quality still blank). One with no source data left is marked missing, so this
    isn't retried on every read - the recompute endpoints can still try again later."""
    if course.elevation_quality:
        return
    if not rebuild_elevation_series(course):
        course.elevation_quality = ELEVATION_QUALITY_MISSING
        course.elevation_series = []
        course.save(update_fields=["elevation_quality", "elevation_series"])


def sections_for(course: Course, settings: SectionSettings | None = None) -> list[dict]:
    """Sections calculated now from the stored series, with the given settings or else the
    owning athlete's - never stored."""
    if course.elevation_quality != ELEVATION_QUALITY_OK:
        return []
    return sections_from_series(course.elevation_series, settings or course.athlete.section_settings())


def climbs_for(course: Course, settings: SectionSettings | None = None) -> list[dict]:
    return climbs_from_sections(sections_for(course, settings))
