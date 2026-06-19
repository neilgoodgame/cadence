from django.shortcuts import get_object_or_404
from rest_framework.exceptions import PermissionDenied, ValidationError
from rest_framework.response import Response
from rest_framework.views import APIView

from activities.models import Activity
from core.auth_context import get_effective_athlete_id
from core.permissions import user_may_read, user_may_write
from workouts.models import Workout

from .models import ScheduledWorkout
from .serializers import (
    ScheduledWorkoutSerializer,
    ScheduledWorkoutUpdateSerializer,
    ScheduleWorkoutCreateSerializer,
)


def _require_read(request, athlete_id):
    sub, _ = get_effective_athlete_id(request)
    if not user_may_read(sub, athlete_id):
        raise PermissionDenied("You do not have access to that athlete's data.")


def _require_write(request, athlete_id):
    sub, _ = get_effective_athlete_id(request)
    if not user_may_write(sub, athlete_id):
        raise PermissionDenied("You do not have write access to that athlete's data.")


class CalendarView(APIView):
    def get(self, request):
        date_from = request.query_params.get("from")
        date_to = request.query_params.get("to")
        if not date_from or not date_to:
            raise ValidationError({"from": "from and to are both required."})

        _, default_athlete_id = get_effective_athlete_id(request)
        athlete_id = request.query_params.get("athlete_id") or default_athlete_id
        _require_read(request, athlete_id)

        entries = ScheduledWorkout.objects.filter(
            athlete_id=athlete_id, date__gte=date_from, date__lte=date_to
        )
        return Response({"data": ScheduledWorkoutSerializer(entries, many=True).data})


class ScheduledWorkoutListCreateView(APIView):
    def post(self, request):
        serializer = ScheduleWorkoutCreateSerializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        data = serializer.validated_data

        sub, _ = get_effective_athlete_id(request)
        athlete_id = data["athlete_id"]
        _require_write(request, athlete_id)

        workout = get_object_or_404(Workout, pk=data["workout_id"], created_by_id=athlete_id)

        scheduled = ScheduledWorkout.objects.create(
            workout=workout,
            athlete_id=athlete_id,
            assigned_by_id=sub if sub != athlete_id else None,
            date=data["date"],
            time_of_day=data.get("time_of_day", ""),
        )
        return Response(ScheduledWorkoutSerializer(scheduled).data, status=201)


class ScheduledWorkoutDetailView(APIView):
    def patch(self, request, id):
        scheduled = get_object_or_404(ScheduledWorkout, pk=id)
        sub, _ = get_effective_athlete_id(request)
        if not user_may_write(sub, scheduled.athlete_id):
            raise PermissionDenied("You do not have write access to that athlete's data.")

        serializer = ScheduledWorkoutUpdateSerializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        data = serializer.validated_data

        update_fields = []
        if "date" in data:
            scheduled.date = data["date"]
            update_fields.append("date")
        if "activity_id" in data:
            activity = get_object_or_404(Activity, pk=data["activity_id"], athlete_id=scheduled.athlete_id)
            scheduled.activity = activity
            scheduled.status = "completed"
            update_fields.extend(["activity", "status"])

        if update_fields:
            scheduled.save(update_fields=update_fields)
        return Response(ScheduledWorkoutSerializer(scheduled).data)

    def delete(self, request, id):
        scheduled = get_object_or_404(ScheduledWorkout, pk=id)
        sub, _ = get_effective_athlete_id(request)
        if not user_may_write(sub, scheduled.athlete_id):
            raise PermissionDenied("You do not have write access to that athlete's data.")
        scheduled.delete()
        return Response(status=204)
