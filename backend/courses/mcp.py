"""MCP tools for courses - read-only, mirrors races/mcp.py's shape."""

from typing import Any

from authn.mcp_scopes import ACTIVITIES_READ
from authn.mcp_toolset import ScopedMCPToolset

from .models import Course


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
        """Get one course's full detail - climbs, elevation profile summary, and the
        races run on it."""
        self._require_scope(ACTIVITIES_READ)
        athlete_id = self._effective_athlete_id()
        self._require_read(athlete_id)

        course = Course.objects.filter(athlete_id=athlete_id, pk=course_id).first()
        if course is None:
            return {"error": "not_found"}

        return {
            "id": course.id,
            "name": course.name,
            "location_name": course.location_name,
            "distance_km": course.distance_km,
            "elevation_gain_m": course.elevation_gain_m,
            "elevation_loss_m": course.elevation_loss_m,
            "is_loop": course.is_loop,
            "climbs": course.climbs,
            "races": [{"id": r.id, "name": r.name, "date": r.date.isoformat()} for r in course.races.order_by("-date")],
        }
