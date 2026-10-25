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
from rest_framework.exceptions import PermissionDenied, ValidationError

from activities.models import Tag
from authn.mcp_scopes import ACTIVITIES_READ, WORKOUTS_WRITE
from authn.mcp_toolset import ScopedMCPToolset
from core.units import parse_distance_meters

from .models import Workout, WorkoutTag
from .serializers import build_step_tree
from .views import SORT_OPTIONS, _attach_tags_by_name, _replace_steps, _resolve_folder, build_match_comparison_rows


def _convert_step_distances(steps: list[dict[str, Any]]) -> None:
    """Mutates each step's distance in place from a unit string ("5km") to metres (5000) -
    recurses into repeat groups' children since Django (unlike the Java tool) accepts
    arbitrary nesting depth. Run before _replace_steps, which expects metres like every other
    distance-consuming path (WorkoutCalculations-equivalent, chart preview) always has.
    """
    for step in steps:
        if "distance" in step:
            step["distance"] = parse_distance_meters(step["distance"], "distance")
        children = step.get("children")
        if children:
            _convert_step_distances(children)


def _require_target_scale(steps: list[dict[str, Any]]) -> None:
    """Catches the specific mistake seen live: a caller passing a 0-1 fraction (0.65) where this
    tool expects a 0-100 percentage (65) - every real target is well above 1 (even an easy
    recovery zone is tens of percent), so any non-null value in (0, 1) is unambiguously wrong
    scale, not a legitimately tiny target. Silently accepting it produced a workout whose
    computed TSS rounded to 0 and whose power target rounded to a couple of watts, with no error
    anywhere to catch it. Recurses into repeat groups' children since Django (unlike the Java
    tool) accepts arbitrary nesting depth.
    """
    for step in steps:
        for field in ("target_low", "target_high"):
            value = step.get(field)
            if isinstance(value, (int, float)) and 0 < value < 1:
                raise ValidationError(
                    {
                        field: f"{field} must be a percentage on a 0-100 scale (e.g. 65 for 65%), not a fraction (e.g. 0.65)."
                    }
                )
        children = step.get("children")
        if children:
            _require_target_scale(children)


def _workout_summary(workout: Workout) -> dict[str, Any]:
    return {
        "id": workout.id,
        "name": workout.name,
        "sport": workout.sport,
        "type": workout.type,
        "duration": workout.duration,
        "tss": workout.tss,
        "folder_id": workout.folder_id,
        "tags": list(workout.tags.order_by("name").values_list("name", flat=True)),
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
            workouts = workouts.filter(workout_tags__tag__name=tag)
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
        %-of-threshold range on a 0-100 scale, e.g. 65 for 65% of threshold (NOT 0.65) - equal
        values for a flat target. distance takes a unit suffix - e.g. "400m", "5km", "3.1mi" -
        the unit is required."""
        self._require_scope(WORKOUTS_WRITE)
        athlete_id = self._effective_athlete_id()
        self._require_write(athlete_id)

        if not name or not name.strip():
            raise ValidationError({"name": "name is required."})
        if sport not in dict(Workout.SPORT_CHOICES):
            raise ValidationError({"sport": "sport must be bike or run."})
        _require_target_scale(steps)
        _convert_step_distances(steps)

        workout = Workout.objects.create(created_by_id=athlete_id, name=name, sport=sport, folder=None)
        _attach_tags_by_name(workout, athlete_id, tags or [])
        _replace_steps(workout, steps)
        return _workout_summary(workout)

    def update_workout(
        self,
        workout_id: str,
        name: str | None = None,
        steps: list[dict[str, Any]] | None = None,
        folder_id: str | None = None,
    ) -> dict[str, Any]:
        """Rename or re-author an existing saved workout. Only the fields you pass are changed -
        omit a field to leave it as-is. Passing steps replaces the entire step tree (same shape
        as create_workout's steps argument); duration and TSS are recomputed from it. Pass
        folder_id="" to clear the folder (omit folder_id entirely to leave it untouched). Tags
        are managed separately - see tag_workout/untag_workout. The workout's sport cannot be
        changed after creation - delete and recreate it instead. Edits do not affect
        already-completed activities matched to this workout."""
        self._require_scope(WORKOUTS_WRITE)
        workout = get_object_or_404(Workout, pk=workout_id)
        self._require_write(workout.created_by_id)

        update_fields = []
        if name is not None:
            if not name.strip():
                raise ValidationError({"name": "name is required."})
            workout.name = name
            update_fields.append("name")
        if folder_id is not None:
            workout.folder = _resolve_folder(folder_id, workout.created_by_id)
            update_fields.append("folder")
        if update_fields:
            workout.save(update_fields=[*update_fields, "updated_at"])

        if steps is not None:
            _require_target_scale(steps)
            _convert_step_distances(steps)
            _replace_steps(workout, steps)

        return _workout_summary(workout)

    def delete_workout(self, workout_id: str) -> dict[str, Any]:
        """Permanently delete a workout from the library. Any scheduled calendar instances of
        this workout are deleted too; completed activities previously matched to it keep their
        history but lose the match link. This cannot be undone."""
        self._require_scope(WORKOUTS_WRITE)
        workout = get_object_or_404(Workout, pk=workout_id)
        self._require_write(workout.created_by_id)
        workout.delete()
        return {"id": workout_id, "deleted": True}

    def tag_workout(self, workout_id: str, name: str) -> dict[str, Any]:
        """Attach a tag to a workout (from list_workouts/get_workout) by name - creates the tag
        if the athlete doesn't already have one with this exact name. Workouts and activities
        share the same tag catalog (see list_tags), so a tag created here can also be attached
        to an activity, and vice versa. A no-op if the workout already carries this tag."""
        self._require_scope(WORKOUTS_WRITE)
        if not name or not name.strip():
            raise ValidationError({"name": "name cannot be empty."})
        workout = get_object_or_404(Workout, pk=workout_id)
        self._require_write(workout.created_by_id)
        tag, _created = Tag.objects.get_or_create(
            athlete_id=workout.created_by_id, name=name, defaults={"origin": "manual"}
        )
        WorkoutTag.objects.get_or_create(workout=workout, tag=tag)
        return _workout_summary(workout)

    def untag_workout(self, workout_id: str, tag_id: str) -> dict[str, Any]:
        """Remove a tag from a workout (from list_workouts/get_workout) - the tag itself stays in
        the catalog, still available for other workouts/activities (see delete_tag to remove it
        entirely instead)."""
        self._require_scope(WORKOUTS_WRITE)
        workout = get_object_or_404(Workout, pk=workout_id)
        self._require_write(workout.created_by_id)
        link = get_object_or_404(WorkoutTag, workout=workout, tag_id=tag_id)
        if link.tag.origin == "auto":
            raise PermissionDenied("Auto-applied tags cannot be removed.")
        link.delete()
        return _workout_summary(workout)

    def get_workout_matches(self, workout_id: str) -> dict[str, Any]:
        """Compare every completed activity matched to a saved workout - average power, average
        heart rate, aerobic efficiency (EF = avg_power / avg_hr), average power/HR for just the
        work-interval blocks (excluding warmup/rest/cooldown), environment data (air temp,
        humidity, core temperature), and TSS - chronologically ordered. Useful for tracking
        fitness trends across repeated efforts at the same fixed-structure workout (e.g. "how has
        my EF on this workout changed over time"). Any field can be null for a given activity if
        that data wasn't captured (e.g. no HR strap, or laps never derived against the workout)."""
        self._require_scope(ACTIVITIES_READ)
        workout = get_object_or_404(Workout, pk=workout_id)
        self._require_read(workout.created_by_id)

        rows = build_match_comparison_rows(workout_id)
        for row in rows:
            row["date"] = row["date"].isoformat()
        return {"data": rows}
