"""MCP tools for the workout library - mirrors the Java backend's mcp/tools/workouts/
WorkoutReadTools.java and WorkoutWriteTools.java. Reuses workouts/views.py's and
workouts/serializers.py's existing logic directly, including step-tree validation
(clean_step_tree, via _replace_steps) - unlike the Java tool, which had to cap nested repeat
groups at one level to work around a schema-generation bug, this accepts true arbitrary-depth
nesting since clean_step_tree already supports it and MCPToolset's schema generation only needs
a plain `list[dict[str, Any]]` type hint, not a fully-typed recursive model.
"""

from typing import Any

from django.db.models import Count
from django.shortcuts import get_object_or_404
from rest_framework.exceptions import ValidationError

from authn.mcp_scopes import ACTIVITIES_READ, WORKOUTS_WRITE
from authn.mcp_toolset import ScopedMCPToolset

from .models import Workout
from .serializers import build_step_tree
from .views import SORT_OPTIONS, _replace_steps


def _workout_summary(workout: Workout) -> dict[str, Any]:
    return {
        "id": workout.id,
        "name": workout.name,
        "sport": workout.sport,
        "type": workout.type,
        "duration": workout.duration,
        "tss": workout.tss,
        "folder_id": workout.folder_id,
        "tags": workout.tags,
        "chart_preview": workout.chart_preview,
        "updated_at": workout.updated_at.isoformat(),
    }


class WorkoutMCPTools(ScopedMCPToolset):
    def list_workouts(
        self,
        folder_id: str | None = None,
        tag: str | None = None,
        sport: str | None = None,
        search: str | None = None,
        sort: str = "recent",
    ) -> dict[str, Any]:
        """List the authenticated athlete's saved workout library entries (structured interval
        sessions, not completed activities) - optionally filtered by folder, tag, sport, or a
        name search."""
        self._require_scope(ACTIVITIES_READ)
        athlete_id = self._effective_athlete_id()
        self._require_read(athlete_id)

        workouts = Workout.objects.filter(created_by_id=athlete_id)
        if folder_id:
            workouts = workouts.filter(folder_id=folder_id)
        if tag:
            workouts = workouts.filter(tags__contains=[tag])
        if sport:
            workouts = workouts.filter(sport=sport)
        if search:
            workouts = workouts.filter(name__icontains=search)

        if sort == "used":
            workouts = workouts.annotate(uses=Count("scheduled_workouts")).order_by("-uses")
        else:
            if sort not in SORT_OPTIONS:
                raise ValidationError({"sort": "Must be one of recent, name, duration, tss, used."})
            workouts = workouts.order_by(*SORT_OPTIONS[sort])

        # Wrapped in {"data": [...]} rather than returned as a bare list - see
        # activities/mcp.py::get_activity_laps's comment for why.
        return {"data": [_workout_summary(w) for w in workouts]}

    def get_workout(self, workout_id: str) -> dict[str, Any]:
        """Get full detail on a single saved workout by id, including its structured interval
        step tree (warmup/blocks/repeats/cooldown with targets)."""
        self._require_scope(ACTIVITIES_READ)
        workout = get_object_or_404(Workout, pk=workout_id)
        self._require_read(workout.created_by_id)

        data = _workout_summary(workout)
        data["steps"] = build_step_tree(workout)
        return data

    def create_workout(
        self, name: str, sport: str, steps: list[dict[str, Any]], tags: list[str] | None = None
    ) -> dict[str, Any]:
        """Create a structured interval workout in the athlete's workout library (not scheduled
        to a date - use schedule_workout for that afterwards). Each step is either a leaf (kind:
        warmup/block/rec/cool, with an end_type time/distance/manual and a target_type power/hr/
        pace/cadence/open) or a repeat group (kind: repeat, with a repeat count and nested
        children - no end_type/target on the group itself). target_low/target_high are a
        %-of-threshold range (equal values for a flat target)."""
        self._require_scope(WORKOUTS_WRITE)
        athlete_id = self._effective_athlete_id()
        self._require_write(athlete_id)

        if not name or not name.strip():
            raise ValidationError({"name": "name is required."})
        if sport not in dict(Workout.SPORT_CHOICES):
            raise ValidationError({"sport": "sport must be bike or run."})

        workout = Workout.objects.create(created_by_id=athlete_id, name=name, sport=sport, folder=None, tags=tags or [])
        _replace_steps(workout, steps)
        return _workout_summary(workout)
