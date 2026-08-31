from typing import Any

from django.db.models import Count
from django.shortcuts import get_object_or_404
from rest_framework.exceptions import PermissionDenied, ValidationError
from rest_framework.request import Request
from rest_framework.response import Response
from rest_framework.views import APIView

from activities.models import Activity
from athletes.zones import reference_for
from core.auth_context import get_effective_athlete_id
from core.permissions import user_may_read, user_may_write

from .calculations import compute_chart_preview, compute_duration_and_tss, normalize_power_units
from .models import Workout, WorkoutFolder, WorkoutStep
from .serializers import (
    WorkoutCreateSerializer,
    WorkoutDetailSerializer,
    WorkoutFolderSerializer,
    WorkoutMatchSerializer,
    WorkoutSerializer,
    WorkoutUpdateSerializer,
    clean_step_tree,
)

MATCH_METHODS = ("auto", "manual", "all")
SORT_OPTIONS = {
    "recent": ["-updated_at"],
    "name": ["name"],
    "duration": ["-duration"],
    "tss": ["-tss"],
}


def _closeness(actual: float, planned: float) -> float | None:
    if not planned:
        return None
    return round(max(0.0, min(1.0, 1 - abs(actual - planned) / planned)), 2)


def _is_auto_matched(activity: Activity) -> bool:
    return activity.tags.filter(name="Auto-matched", origin="auto").exists()


def _require_read(request: Request, athlete_id: str) -> None:
    sub, _ = get_effective_athlete_id(request)
    if not user_may_read(sub, athlete_id):
        raise PermissionDenied("You do not have access to that athlete's data.")


def _require_write(request: Request, athlete_id: str) -> None:
    sub, _ = get_effective_athlete_id(request)
    if not user_may_write(sub, athlete_id):
        raise PermissionDenied("You do not have write access to that athlete's data.")


def _create_steps(workout: Workout, steps_data: list[dict[str, Any]], parent: WorkoutStep | None = None) -> None:
    for index, step in enumerate(steps_data):
        row = WorkoutStep.objects.create(
            workout=workout,
            parent=parent,
            order=index,
            kind=step["kind"],
            end_type=step.get("end_type"),
            duration=step.get("duration"),
            distance=step.get("distance"),
            target_type=step.get("target_type"),
            target_low=step.get("target_low"),
            target_high=step.get("target_high"),
            power_unit=step.get("power_unit") or "pct_ftp",
            target2_type=step.get("target2_type") or "none",
            target2_low=step.get("target2_low"),
            target2_high=step.get("target2_high"),
            repeat=step.get("repeat") or 1,
            note=step.get("note") or "",
        )
        if step["kind"] == "repeat":
            _create_steps(workout, step.get("children") or [], parent=row)


def _replace_steps(workout: Workout, steps_data: list[dict[str, Any]]) -> None:
    cleaned = clean_step_tree(steps_data)
    workout.steps.all().delete()
    _create_steps(workout, cleaned)
    zone_type = "bike_power" if workout.sport == "bike" else "run_power"
    power_reference = reference_for(workout.created_by, zone_type)
    pace_reference = reference_for(workout.created_by, "pace")
    normalized = normalize_power_units(cleaned, power_reference)
    duration, tss = compute_duration_and_tss(normalized, workout.sport, pace_reference)
    workout.duration = duration
    workout.tss = tss
    workout.chart_preview = compute_chart_preview(normalized, workout.sport, pace_reference)
    workout.save(update_fields=["duration", "tss", "chart_preview", "updated_at"])


def _resolve_folder(folder_id: str | None, athlete_id: str) -> WorkoutFolder | None:
    if not folder_id:
        return None
    return get_object_or_404(WorkoutFolder, pk=folder_id, created_by_id=athlete_id)


class WorkoutListView(APIView):
    def get(self, request: Request) -> Response:
        sub, athlete_id = get_effective_athlete_id(request)
        _require_read(request, athlete_id)

        workouts = Workout.objects.filter(created_by_id=athlete_id)

        folder_id = request.query_params.get("folder_id")
        if folder_id:
            workouts = workouts.filter(folder_id=folder_id)
        tag = request.query_params.get("tag")
        if tag:
            workouts = workouts.filter(tags__contains=[tag])
        sport = request.query_params.get("sport")
        if sport:
            workouts = workouts.filter(sport=sport)
        search = request.query_params.get("search")
        if search:
            workouts = workouts.filter(name__icontains=search)

        sort = request.query_params.get("sort", "recent")
        if sort == "used":
            workouts = workouts.annotate(uses=Count("scheduled_workouts")).order_by("-uses")
        else:
            if sort not in SORT_OPTIONS:
                raise ValidationError({"sort": "Must be one of recent, name, duration, tss, used."})
            workouts = workouts.order_by(*SORT_OPTIONS[sort])

        return Response({"data": WorkoutSerializer(workouts, many=True).data})

    def post(self, request: Request) -> Response:
        sub, athlete_id = get_effective_athlete_id(request)
        _require_write(request, athlete_id)

        serializer = WorkoutCreateSerializer(data=request.data)
        serializer.is_valid(raise_exception=True)

        workout = Workout.objects.create(
            created_by_id=athlete_id,
            name=serializer.validated_data["name"],
            sport=serializer.validated_data["sport"],
            folder=_resolve_folder(serializer.validated_data.get("folder_id"), athlete_id),
            tags=serializer.validated_data.get("tags", []),
        )
        _replace_steps(workout, serializer.validated_data["steps"])

        return Response(WorkoutSerializer(workout).data, status=201)


class WorkoutDetailView(APIView):
    def get(self, request: Request, id: str) -> Response:
        sub, _ = get_effective_athlete_id(request)
        workout = get_object_or_404(Workout, pk=id)
        if not user_may_read(sub, workout.created_by_id):
            raise PermissionDenied("You do not have access to that athlete's data.")
        return Response(WorkoutDetailSerializer(workout).data)

    def patch(self, request: Request, id: str) -> Response:
        sub, _ = get_effective_athlete_id(request)
        workout = get_object_or_404(Workout, pk=id)
        if not user_may_write(sub, workout.created_by_id):
            raise PermissionDenied("You do not have write access to that athlete's data.")

        serializer = WorkoutUpdateSerializer(data=request.data)
        serializer.is_valid(raise_exception=True)

        update_fields = []
        if "name" in serializer.validated_data:
            workout.name = serializer.validated_data["name"]
            update_fields.append("name")
        if "folder_id" in serializer.validated_data:
            workout.folder = _resolve_folder(serializer.validated_data["folder_id"], workout.created_by_id)
            update_fields.append("folder")
        if "tags" in serializer.validated_data:
            workout.tags = serializer.validated_data["tags"]
            update_fields.append("tags")
        if update_fields:
            workout.save(update_fields=[*update_fields, "updated_at"])

        if "steps" in serializer.validated_data:
            _replace_steps(workout, serializer.validated_data["steps"])

        return Response(WorkoutSerializer(workout).data)

    def delete(self, request: Request, id: str) -> Response:
        sub, _ = get_effective_athlete_id(request)
        workout = get_object_or_404(Workout, pk=id)
        if not user_may_write(sub, workout.created_by_id):
            raise PermissionDenied("You do not have write access to that athlete's data.")
        workout.delete()
        return Response(status=204)


class WorkoutFolderListView(APIView):
    def get(self, request: Request) -> Response:
        sub, athlete_id = get_effective_athlete_id(request)
        _require_read(request, athlete_id)
        folders = WorkoutFolder.objects.filter(created_by_id=athlete_id).annotate(count=Count("workouts"))
        return Response({"data": WorkoutFolderSerializer(folders, many=True).data})

    def post(self, request: Request) -> Response:
        sub, athlete_id = get_effective_athlete_id(request)
        _require_write(request, athlete_id)

        name = (request.data.get("name") or "").strip()
        if not name:
            raise ValidationError({"name": "This field is required."})
        if WorkoutFolder.objects.filter(created_by_id=athlete_id, name=name).exists():
            raise ValidationError({"name": "A folder with this name already exists."})

        folder = WorkoutFolder.objects.create(created_by_id=athlete_id, name=name)
        folder.count = 0
        return Response(WorkoutFolderSerializer(folder).data, status=201)


class WorkoutFolderDetailView(APIView):
    def patch(self, request: Request, id: str) -> Response:
        sub, _ = get_effective_athlete_id(request)
        folder = get_object_or_404(WorkoutFolder, pk=id)
        if not user_may_write(sub, folder.created_by_id):
            raise PermissionDenied("You do not have write access to that athlete's data.")

        name = request.data.get("name")
        if name is not None:
            name = name.strip()
            if (
                WorkoutFolder.objects.filter(created_by_id=folder.created_by_id, name=name)
                .exclude(pk=folder.pk)
                .exists()
            ):
                raise ValidationError({"name": "A folder with this name already exists."})
            folder.name = name
            folder.save(update_fields=["name"])

        folder.count = folder.workouts.count()
        return Response(WorkoutFolderSerializer(folder).data)

    def delete(self, request: Request, id: str) -> Response:
        sub, _ = get_effective_athlete_id(request)
        folder = get_object_or_404(WorkoutFolder, pk=id)
        if not user_may_write(sub, folder.created_by_id):
            raise PermissionDenied("You do not have write access to that athlete's data.")
        folder.delete()
        return Response(status=204)


class WorkoutMatchListView(APIView):
    def get(self, request: Request, id: str) -> Response:
        sub, _ = get_effective_athlete_id(request)
        workout = get_object_or_404(Workout, pk=id)
        if not user_may_read(sub, workout.created_by_id):
            raise PermissionDenied("You do not have access to that athlete's data.")

        method = request.query_params.get("method", "all")
        if method not in MATCH_METHODS:
            raise ValidationError({"method": "Must be one of auto, manual, all."})

        matches = []
        for activity in Activity.objects.filter(workout_id=id).order_by("-start_date"):
            is_auto = _is_auto_matched(activity)
            activity_method = "auto" if is_auto else "manual"
            if method != "all" and activity_method != method:
                continue
            matches.append(
                {
                    "activity_id": activity.id,
                    "name": activity.name,
                    "date": activity.start_date.date(),
                    "method": activity_method,
                    "confidence": _closeness(activity.moving_time, workout.duration) if is_auto else None,
                    "compliance": _closeness(activity.tss, workout.tss),
                    "tss": activity.tss,
                    "moving_time": activity.moving_time,
                    "distance_km": activity.distance_km,
                    "avg_power": activity.avg_power,
                }
            )
        return Response({"data": WorkoutMatchSerializer(matches, many=True).data})
