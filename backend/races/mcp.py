"""MCP tools for goal races - mirrors the Java backend's mcp/tools/races/RaceTools.java. Reuses
races/views.py's existing queryset logic directly.
"""

from datetime import timedelta
from typing import Any

from rest_framework.exceptions import ValidationError

from activities.mcp import convert_custom_steps_input
from authn.mcp_scopes import ACTIVITIES_READ, ACTIVITIES_WRITE
from authn.mcp_toolset import ScopedMCPToolset
from core.auth_context import get_effective_athlete_id

from . import splits as splits_module
from .blocks import get_race_block
from .comments import resolve_parent_comment, snapshot_role
from .models import Race, RaceComment
from .serializers import RaceCommentCreateSerializer, RaceCommentSerializer
from .views import SPLITS_MODES
from .weather import forecast_for


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
        return splits_module.get_custom_splits(race, convert_custom_steps_input(steps))

    def get_race_splits(self, race_id: str, mode: str = "laps") -> dict[str, Any]:
        """Get splits for a goal race's linked activity - manual laps, fixed 1km/5km splits, or
        (elevation) one split per flat/climb/descent section of the race's linked course. Each
        split's delta is against the race's own goal pace/target power (or its activity's
        average, if there's no goal/target). mode defaults to "laps"; use get_race_custom_splits
        instead for an arbitrary list of steps (e.g. "the first mile, then every 2km")."""
        self._require_scope(ACTIVITIES_READ)
        athlete_id = self._effective_athlete_id()
        self._require_read(athlete_id)

        race = Race.objects.filter(pk=race_id, athlete_id=athlete_id).first()
        if race is None:
            return {"error": "not_found"}
        if mode not in SPLITS_MODES:
            raise ValidationError({"mode": f"Must be one of {', '.join(SPLITS_MODES)}."})
        return splits_module.get_splits(race, mode)

    def get_race_weather(self, race_id: str) -> dict[str, Any]:
        """Get the race-day weather forecast, resolved via the race's linked course's start
        point. available is false (every other field null) if the race has no course, the
        course has no resolved start point, or the race is more than 10 days away."""
        self._require_scope(ACTIVITIES_READ)
        athlete_id = self._effective_athlete_id()
        self._require_read(athlete_id)

        race = Race.objects.filter(pk=race_id, athlete_id=athlete_id).first()
        if race is None:
            return {"error": "not_found"}
        return forecast_for(race)

    def list_race_comments(self, race_id: str) -> dict[str, Any]:
        """List the coach/athlete plan comments on a goal race (from list_races), oldest first -
        who wrote each one, their role (athlete/coach), and which training week they're about,
        if any."""
        self._require_scope(ACTIVITIES_READ)
        athlete_id = self._effective_athlete_id()
        self._require_read(athlete_id)

        race = Race.objects.filter(pk=race_id, athlete_id=athlete_id).first()
        if race is None:
            return {"error": "not_found"}
        comments = race.comments.select_related("author").order_by("created_at")
        return {"data": RaceCommentSerializer(comments, many=True).data}

    def post_race_comment(
        self, race_id: str, text: str, week: int | None = None, parent_comment_id: str | None = None
    ) -> dict[str, Any]:
        """Post a plan comment on a goal race (from list_races) - visible to the athlete and
        their coach. Optionally tag it to a training week (1-based). Omit parent_comment_id for
        a top-level comment, or pass an existing top-level comment's id (from
        list_race_comments) to reply to it - single-level threading only, replying to a reply is
        rejected."""
        self._require_scope(ACTIVITIES_WRITE)
        athlete_id = self._effective_athlete_id()
        self._require_write(athlete_id)

        race = Race.objects.filter(pk=race_id, athlete_id=athlete_id).first()
        if race is None:
            return {"error": "not_found"}

        serializer = RaceCommentCreateSerializer(data={"text": text, "week": week, "parent_id": parent_comment_id})
        serializer.is_valid(raise_exception=True)
        parent = resolve_parent_comment(race, serializer.validated_data.get("parent_id"))

        # The comment's author is always the calling credential's own sub - a coach's delegated
        # token resolves athlete_id to the athlete being coached (used above for the ownership
        # check), but the comment itself is attributed to the coach, same as post_activity_comment.
        author_id, _ = get_effective_athlete_id(self.request)
        comment = RaceComment.objects.create(
            race=race,
            author_id=author_id,
            role=snapshot_role(race, author_id),
            parent=parent,
            text=serializer.validated_data["text"],
            week=serializer.validated_data.get("week"),
        )
        return RaceCommentSerializer(comment).data
