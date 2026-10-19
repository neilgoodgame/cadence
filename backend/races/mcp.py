"""MCP tools for goal races - mirrors the Java backend's mcp/tools/races/RaceTools.java. Reuses
races/views.py's existing queryset logic directly.
"""

from datetime import timedelta
from typing import Any

from rest_framework.exceptions import ValidationError

from activities.mcp import convert_custom_steps_input
from authn.mcp_scopes import ACTIVITIES_READ
from authn.mcp_toolset import ScopedMCPToolset

from . import splits as splits_module
from .blocks import get_race_block
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
                    "priority": r.priority,
                    "course_id": r.course_id,
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

    def get_race_block(self, race_id: str) -> dict[str, Any]:
        """Get an A race's training block (weekly load/distance, race-day form,
        predicted time, key sessions) or a B/C race's 5-week window around it."""
        self._require_scope(ACTIVITIES_READ)
        athlete_id = self._effective_athlete_id()
        self._require_read(athlete_id)

        race = Race.objects.filter(pk=race_id, athlete_id=athlete_id).first()
        if race is None:
            return {"error": "not_found"}
        return get_race_block(race)

    def get_race_custom_splits(self, race_id: str, steps: list[dict[str, Any]]) -> dict[str, Any]:
        """Get splits for a goal race's linked activity from your own list of steps instead of a
        fixed distance - e.g. "the first mile, then every 2km after that", or steps that mirror
        a workout's own structure. Each step is either `{"end_type": "time", "duration_s": N}`
        (N seconds) or `{"end_type": "distance", "distance": "1mi"|"2km"|"800m"}` (a number with
        a unit) - add a "label" to name one (e.g. "Mile 1"). Steps are consumed in order from the
        start of the recording, each picking up where the previous one left off, so they can
        never overlap or be out of order - a step with no recording left to consume (the
        activity ended early) is just dropped, and any recording left over after the last step
        becomes one unlabeled trailing split. Each split's delta is against the race's own goal
        pace/target power (or its activity's average, if there's no goal/target) - see
        get_activity_custom_splits for a plain activity with nothing to compare against."""
        self._require_scope(ACTIVITIES_READ)
        athlete_id = self._effective_athlete_id()
        self._require_read(athlete_id)

        race = Race.objects.filter(pk=race_id, athlete_id=athlete_id).first()
        if race is None:
            return {"error": "not_found"}
        try:
            return splits_module.get_custom_splits(race, convert_custom_steps_input(steps))
        except ValueError as exc:
            raise ValidationError({"steps": str(exc)}) from exc
