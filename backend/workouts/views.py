from typing import Any

from django.shortcuts import get_object_or_404
from rest_framework.exceptions import PermissionDenied, ValidationError
from rest_framework.request import Request
from rest_framework.response import Response
from rest_framework.views import APIView

from activities.models import Activity
from core.auth_context import get_effective_athlete_id
from core.permissions import user_may_read, user_may_write

from .calculations import compute_duration_and_tss
from .models import Workout, WorkoutStep
from .serializers import (
    WorkoutCreateSerializer,
    WorkoutDetailSerializer,
    WorkoutMatchSerializer,
    WorkoutSerializer,
    WorkoutUpdateSerializer,
)

MATCH_METHODS = ("auto", "manual", "all")


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


def _replace_steps(workout: Workout, steps_data: list[dict[str, Any]]) -> None:
    workout.steps.all().delete()
    WorkoutStep.objects.bulk_create(
        [
            WorkoutStep(
                workout=workout,
                order=index,
                kind=step["kind"],
                end_type=step["end_type"],
                duration=step.get("duration"),
                distance=step.get("distance"),
                target_pct=step.get("target_pct"),
                repeat=step.get("repeat") or 1,
            )
            for index, step in enumerate(steps_data)
        ]
    )
    duration, tss = compute_duration_and_tss(steps_data)
    workout.duration = duration
    workout.tss = tss
    workout.save(update_fields=["duration", "tss"])


class WorkoutListView(APIView):
    def get(self, request: Request) -> Response:
        sub, athlete_id = get_effective_athlete_id(request)
        _require_read(request, athlete_id)
        workouts = Workout.objects.filter(created_by_id=athlete_id)
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

        if "name" in serializer.validated_data:
            workout.name = serializer.validated_data["name"]
            workout.save(update_fields=["name"])

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
                }
            )
        return Response({"data": WorkoutMatchSerializer(matches, many=True).data})
