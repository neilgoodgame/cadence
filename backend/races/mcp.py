"""MCP tools for goal races - mirrors the Java backend's mcp/tools/races/RaceTools.java. Reuses
races/views.py's existing queryset logic directly.
"""

from datetime import timedelta
from typing import Any

from activities.mcp import convert_custom_steps_input
from athletes import threshold_suggestions
from authn.mcp_scopes import ACTIVITIES_READ, CALENDAR_WRITE
from authn.mcp_toolset import ScopedMCPToolset

from . import splits as splits_module
from .blocks import get_race_block
from .models import Race
from .serializers import RaceCreateSerializer, RaceSerializer


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

    def create_race(
        self,
        name: str,
        date: str,
        sport: str | None = None,
        priority: str = "A",
        distance_km: float | None = None,
        goal_time: str | None = None,
        course_id: str | None = None,
        key_session_tag_id: str | None = None,
        notes: str = "",
        url: str | None = None,
        target_power: int | None = None,
        block_weeks_requested: int | None = None,
        base_weeks_requested: int | None = None,
        build_weeks_requested: int | None = None,
        peak_weeks_requested: int | None = None,
        taper_weeks_requested: int | None = None,
    ) -> dict[str, Any]:
        """Create a goal race on the calendar. date is "YYYY-MM-DD", goal_time is "H:MM:SS".
        priority is A (the goal race - gets its own training block), B or C (tune-ups, nested
        inside whichever A race's block they fall in). For an A race only: block_weeks_requested
        and base/build/peak/taper_weeks_requested override its training-block length and
        phase split - the four phase fields must either all be set together (summing exactly to
        block_weeks_requested) or all be left unset, in which case the block length falls back to
        the athlete's distance-based default (or their flat preference), and the phase split to a
        proportional Base/Build/Peak/Taper. These five fields are rejected on a B/C race."""
        self._require_scope(CALENDAR_WRITE)
        athlete_id = self._effective_athlete_id()
        self._require_write(athlete_id)

        payload = {
            "name": name,
            "date": date,
            "priority": priority,
            "notes": notes,
            "sport": sport,
            "distance_km": distance_km,
            "goal_time": goal_time,
            "course_id": course_id,
            "key_session_tag_id": key_session_tag_id,
            "url": url,
            "target_power": target_power,
            "block_weeks_requested": block_weeks_requested,
            "base_weeks_requested": base_weeks_requested,
            "build_weeks_requested": build_weeks_requested,
            "peak_weeks_requested": peak_weeks_requested,
            "taper_weeks_requested": taper_weeks_requested,
        }
        serializer = RaceCreateSerializer(data={k: v for k, v in payload.items() if v is not None})
        serializer.is_valid(raise_exception=True)
        race = Race.objects.create(athlete_id=athlete_id, **serializer.validated_data)
        threshold_suggestions.invalidate(athlete_id)
        return RaceSerializer(race).data

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
        return splits_module.get_custom_splits(race, convert_custom_steps_input(steps))
