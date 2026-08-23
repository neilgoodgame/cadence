"""MCP tools for the training calendar - mirrors the Java backend's
mcp/tools/scheduling/CalendarReadTools.java and ScheduledWorkoutWriteTools.java. Reuses
scheduling/views.py's existing queryset logic directly.
"""

from typing import Any

from django.shortcuts import get_object_or_404
from django.utils.dateparse import parse_date
from rest_framework.exceptions import ValidationError

from activities.models import Activity
from authn.mcp_scopes import ACTIVITIES_READ, CALENDAR_WRITE
from authn.mcp_toolset import ScopedMCPToolset
from core.auth_context import get_effective_athlete_id
from workouts.models import Workout

from .models import ScheduledWorkout

_TIME_OF_DAY_INPUT = {"am": "AM", "mid": "MID", "pm": "PM"}


def _activity_summary(activity: Activity) -> dict[str, Any]:
    return {
        "id": activity.id,
        "name": activity.name,
        "sport": activity.sport,
        "start_date": activity.start_date.isoformat(),
        "moving_time": activity.moving_time,
        "distance_km": activity.distance_km,
        "avg_power": activity.avg_power,
        "avg_hr": activity.avg_hr,
        "tss": activity.tss,
        "intensity": activity.intensity,
    }


def _scheduled_workout_entry(scheduled: ScheduledWorkout) -> dict[str, Any]:
    return {
        "id": scheduled.id,
        "workout_id": scheduled.workout_id,
        "athlete_id": scheduled.athlete_id,
        "assigned_by": scheduled.assigned_by_id,
        "date": scheduled.date.isoformat(),
        "time_of_day": scheduled.time_of_day,
        "status": scheduled.status,
        "activity_id": scheduled.activity_id,
    }


class SchedulingMCPTools(ScopedMCPToolset):
    def get_calendar(self, date_from: str, date_to: str) -> dict[str, Any]:
        """Get the authenticated athlete's calendar for a date range: scheduled (planned)
        workouts plus completed activities that weren't scheduled or matched to a planned
        workout. Useful for 'what's on my training plan this week' or 'did I complete everything
        I planned'."""
        self._require_scope(ACTIVITIES_READ)
        athlete_id = self._effective_athlete_id()
        self._require_read(athlete_id)

        if parse_date(date_from) is None:
            raise ValidationError({"date_from": "Must be a date in YYYY-MM-DD format."})
        if parse_date(date_to) is None:
            raise ValidationError({"date_to": "Must be a date in YYYY-MM-DD format."})

        entries = ScheduledWorkout.objects.filter(athlete_id=athlete_id, date__gte=date_from, date__lte=date_to)
        unplanned = Activity.objects.filter(
            athlete_id=athlete_id,
            start_date__date__gte=date_from,
            start_date__date__lte=date_to,
            scheduled_workout_matches__isnull=True,
            parent_activity__isnull=True,
            primary_activity__isnull=True,
        )
        return {
            "scheduled": [_scheduled_workout_entry(e) for e in entries],
            "unplanned_activities": [_activity_summary(a) for a in unplanned],
        }

    def schedule_workout(self, workout_id: str, date: str, time_of_day: str = "mid") -> dict[str, Any]:
        """Put a saved workout (from list_workouts/create_workout) onto the athlete's calendar
        for a specific date."""
        self._require_scope(CALENDAR_WRITE)
        athlete_id = self._effective_athlete_id()
        self._require_write(athlete_id)

        parsed_date = parse_date(date)
        if parsed_date is None:
            raise ValidationError({"date": "Must be a date in YYYY-MM-DD format."})

        normalized_tod = _TIME_OF_DAY_INPUT.get((time_of_day or "mid").strip().lower())
        if normalized_tod is None:
            raise ValidationError({"time_of_day": "time_of_day must be one of: am, mid, pm."})

        workout = get_object_or_404(Workout, pk=workout_id, created_by_id=athlete_id)
        sub, _ = get_effective_athlete_id(self.request)
        scheduled = ScheduledWorkout.objects.create(
            workout=workout,
            athlete_id=athlete_id,
            assigned_by_id=sub if sub != athlete_id else None,
            date=parsed_date,
            time_of_day=normalized_tod,
        )
        return _scheduled_workout_entry(scheduled)
