"""MCP tools for courses - read-only, mirrors races/mcp.py's shape."""

from typing import Any

from authn.mcp_scopes import ACTIVITIES_READ
from authn.mcp_toolset import ScopedMCPToolset

from .elevation import ensure_elevation_series, sections_for
from .models import Course
from .sections import climbs_from_sections


class CourseMCPTools(ScopedMCPToolset):
    def list_courses(self) -> dict[str, Any]:
        """List the authenticated athlete's saved courses - name, location, distance,
        elevation gain and how many races have been run on each."""
        self._require_scope(ACTIVITIES_READ)
        athlete_id = self._effective_athlete_id()
        self._require_read(athlete_id)

        courses = Course.objects.filter(athlete_id=athlete_id)
        return {
            "data": [
                {
                    "id": c.id,
                    "name": c.name,
                    "location_name": c.location_name,
                    "distance_km": c.distance_km,
                    "elevation_gain_m": c.elevation_gain_m,
                    "is_loop": c.is_loop,
                    "race_count": c.races.count(),
                }
                for c in courses
            ]
        }

    def get_course(self, course_id: str) -> dict[str, Any]:
        """Get one course's full detail - the whole course split into flat, climb and
        descent sections (each with distance range, gain/loss, average and steepest-100m
        grade), the climbs among them, and the races run on it. elevation_quality is "ok",
        or "missing"/"sparse" when the course's elevation can't support sections."""
        self._require_scope(ACTIVITIES_READ)
        athlete_id = self._effective_athlete_id()
        self._require_read(athlete_id)

        course = Course.objects.filter(athlete_id=athlete_id, pk=course_id).first()
        if course is None:
            return {"error": "not_found"}
        ensure_elevation_series(course)
        sections = sections_for(course)

        return {
            "id": course.id,
            "name": course.name,
            "location_name": course.location_name,
            "distance_km": course.distance_km,
            "elevation_gain_m": course.elevation_gain_m,
            "elevation_loss_m": course.elevation_loss_m,
            "is_loop": course.is_loop,
            "elevation_quality": course.elevation_quality,
            "sections": sections,
            "climbs": climbs_from_sections(sections),
            "races": [{"id": r.id, "name": r.name, "date": r.date.isoformat()} for r in course.races.order_by("-date")],
        }
