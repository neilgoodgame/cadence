"""MCP tools for goal races - mirrors the Java backend's mcp/tools/races/RaceTools.java. Reuses
races/views.py's existing queryset logic directly.
"""

from datetime import timedelta
from typing import Any

from authn.mcp_scopes import ACTIVITIES_READ
from authn.mcp_toolset import ScopedMCPToolset

from .models import Race


def _format_duration(value: timedelta | None) -> str | None:
    if value is None:
        return None
    total_seconds = int(value.total_seconds())
    hours, remainder = divmod(total_seconds, 3600)
    minutes, seconds = divmod(remainder, 60)
    return f"{hours}:{minutes:02d}:{seconds:02d}"


class RaceMCPTools(ScopedMCPToolset):
    def list_races(self) -> dict[str, Any]:
        """List the authenticated athlete's goal races - name, date, sport, distance, goal/
        result time, and any notes. Useful for planning training around an upcoming event."""
        self._require_scope(ACTIVITIES_READ)
        athlete_id = self._effective_athlete_id()
        self._require_read(athlete_id)

        races = Race.objects.filter(athlete_id=athlete_id)
        # Wrapped in {"data": [...]} rather than returned as a bare list - see
        # activities/mcp.py::get_activity_laps's comment for why.
        return {
            "data": [
                {
                    "id": r.id,
                    "athlete_id": r.athlete_id,
                    "name": r.name,
                    "date": r.date.isoformat(),
                    "sport": r.sport,
                    "distance_km": r.distance_km,
                    "goal_time": _format_duration(r.goal_time),
                    "result_time": _format_duration(r.result_time),
                    "activity_id": r.activity_id,
                    "url": r.url,
                    "results_url": r.results_url,
                    "notes": r.notes,
                }
                for r in races
            ]
        }
