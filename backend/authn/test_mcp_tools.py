"""Regression guard for the MCP tool layer (activities/athletes/gear/races/scheduling/workouts's
mcp.py modules) - mirrors backend_java's McpToolsIntegrationTest.java: covers what the live manual
verification during development already proved works end-to-end through the real /mcp transport
(see docs/mcp-oauth.md's "Testing" section), but as a durable regression guard. Calls tool methods
directly (bypassing the real JSON-RPC transport, already covered by McpAuthChallengeTests in
authn/tests.py) with a lightweight fake request carrying just .user/.auth - the two attributes
core.auth_context's helpers and ScopedMCPToolset actually read.
"""

from types import SimpleNamespace
from typing import Any

from django.test import TestCase
from django.utils import timezone
from rest_framework.exceptions import PermissionDenied, ValidationError

from accounts.models import User
from activities.mcp import ActivityMCPTools
from activities.models import Activity
from athletes.mcp import AthleteMCPTools
from authn.oauth_utils import issue_token_pair
from core.models import generate_id
from gear.mcp import GearMCPTools
from races.mcp import RaceMCPTools
from scheduling.mcp import SchedulingMCPTools
from scheduling.models import ScheduledWorkout
from workouts.mcp import WorkoutMCPTools
from workouts.models import Workout


def _mcp_request(user: User, scope: str) -> Any:
    access_token, _ = issue_token_pair(user, scope=scope)
    return SimpleNamespace(user=user, auth=access_token)


class WorkoutToolsTests(TestCase):
    def test_create_workout_persists_with_correct_duration_and_chart_preview(self) -> None:
        athlete = User.objects.create_user(email="mcp-create-workout@example.cc", password="x", name="Athlete")
        tools = WorkoutMCPTools(request=_mcp_request(athlete, "workouts:write"))

        result = tools.create_workout(
            name="MCP Test",
            sport="bike",
            steps=[
                {
                    "kind": "warmup",
                    "end_type": "time",
                    "duration": 600,
                    "target_type": "power",
                    "target_low": 50,
                    "target_high": 50,
                },
                {
                    "kind": "repeat",
                    "repeat": 3,
                    "children": [
                        {
                            "kind": "block",
                            "end_type": "time",
                            "duration": 180,
                            "target_type": "power",
                            "target_low": 105,
                            "target_high": 105,
                        },
                        {
                            "kind": "rec",
                            "end_type": "time",
                            "duration": 120,
                            "target_type": "power",
                            "target_low": 55,
                            "target_high": 55,
                        },
                    ],
                },
                {
                    "kind": "cool",
                    "end_type": "time",
                    "duration": 300,
                    "target_type": "power",
                    "target_low": 45,
                    "target_high": 45,
                },
            ],
        )

        self.assertEqual(result["duration"], 600 + 3 * (180 + 120) + 300)
        workout = Workout.objects.get(pk=result["id"])
        self.assertEqual(workout.created_by_id, athlete.id)
        # warmup + 3x(block+rec) + cool = 8 leaf points, matching the flattened/unrolled chart preview.
        self.assertEqual(len(workout.chart_preview), 8)

    def test_create_workout_rejects_an_invalid_target_type(self) -> None:
        athlete = User.objects.create_user(email="mcp-create-workout-invalid@example.cc", password="x", name="Athlete")
        tools = WorkoutMCPTools(request=_mcp_request(athlete, "workouts:write"))

        with self.assertRaises(ValidationError):
            tools.create_workout(
                name="Bad",
                sport="bike",
                steps=[{"kind": "block", "end_type": "time", "duration": 100, "target_type": "not-a-real-type"}],
            )

    def test_create_workout_requires_workouts_write_scope(self) -> None:
        athlete = User.objects.create_user(email="mcp-create-workout-scope@example.cc", password="x", name="Athlete")
        tools = WorkoutMCPTools(request=_mcp_request(athlete, "activities:read"))

        with self.assertRaises(PermissionDenied):
            tools.create_workout(name="x", sport="bike", steps=[])

    def test_get_workout_rejects_another_athletes_workout(self) -> None:
        owner = User.objects.create_user(email="mcp-workout-owner@example.cc", password="x", name="Owner")
        outsider = User.objects.create_user(email="mcp-workout-outsider@example.cc", password="x", name="Outsider")
        workout = Workout.objects.create(created_by=owner, name="Owner's workout", sport="bike")

        tools = WorkoutMCPTools(request=_mcp_request(outsider, "activities:read"))
        with self.assertRaises(PermissionDenied):
            tools.get_workout(workout_id=workout.id)

    def test_list_workouts_wraps_result_in_data_key(self) -> None:
        athlete = User.objects.create_user(email="mcp-list-workouts@example.cc", password="x", name="Athlete")
        tools = WorkoutMCPTools(request=_mcp_request(athlete, "activities:read"))

        result = tools.list_workouts()
        self.assertEqual(result, {"data": []})


class SchedulingToolsTests(TestCase):
    def test_schedule_workout_normalizes_time_of_day_and_creates_entry(self) -> None:
        athlete = User.objects.create_user(email="mcp-schedule@example.cc", password="x", name="Athlete")
        workout = Workout.objects.create(created_by=athlete, name="W", sport="bike")
        tools = SchedulingMCPTools(request=_mcp_request(athlete, "calendar:write"))

        result = tools.schedule_workout(workout_id=workout.id, date="2026-09-01", time_of_day="am")

        self.assertEqual(result["time_of_day"], "AM")
        scheduled = ScheduledWorkout.objects.get(pk=result["id"])
        self.assertEqual(scheduled.athlete_id, athlete.id)
        self.assertEqual(scheduled.workout_id, workout.id)

    def test_schedule_workout_rejects_invalid_time_of_day(self) -> None:
        athlete = User.objects.create_user(email="mcp-schedule-bad-tod@example.cc", password="x", name="Athlete")
        workout = Workout.objects.create(created_by=athlete, name="W", sport="bike")
        tools = SchedulingMCPTools(request=_mcp_request(athlete, "calendar:write"))

        with self.assertRaises(ValidationError):
            tools.schedule_workout(workout_id=workout.id, date="2026-09-01", time_of_day="afternoon")

    def test_schedule_workout_requires_calendar_write_scope(self) -> None:
        athlete = User.objects.create_user(email="mcp-schedule-scope@example.cc", password="x", name="Athlete")
        workout = Workout.objects.create(created_by=athlete, name="W", sport="bike")
        tools = SchedulingMCPTools(request=_mcp_request(athlete, "activities:read"))

        with self.assertRaises(PermissionDenied):
            tools.schedule_workout(workout_id=workout.id, date="2026-09-01")

    def test_get_calendar_reports_scheduled_and_unplanned(self) -> None:
        athlete = User.objects.create_user(email="mcp-calendar@example.cc", password="x", name="Athlete")
        workout = Workout.objects.create(created_by=athlete, name="W", sport="bike")
        ScheduledWorkout.objects.create(workout=workout, athlete=athlete, date="2026-09-01", time_of_day="AM")
        Activity.objects.create(
            id=generate_id("act"),
            athlete=athlete,
            sport="bike",
            name="Unplanned ride",
            start_date=timezone.datetime(2026, 9, 2, tzinfo=timezone.get_default_timezone()),
            moving_time=1800,
            distance_km=10,
        )

        tools = SchedulingMCPTools(request=_mcp_request(athlete, "activities:read"))
        result = tools.get_calendar(date_from="2026-09-01", date_to="2026-09-02")

        self.assertEqual(len(result["scheduled"]), 1)
        self.assertEqual(len(result["unplanned_activities"]), 1)


class ActivityToolsTests(TestCase):
    def test_get_activity_rejects_another_athletes_activity(self) -> None:
        owner = User.objects.create_user(email="mcp-activity-owner@example.cc", password="x", name="Owner")
        outsider = User.objects.create_user(email="mcp-activity-outsider@example.cc", password="x", name="Outsider")
        activity = Activity.objects.create(
            id=generate_id("act"),
            athlete=owner,
            sport="bike",
            name="Owner's ride",
            start_date=timezone.now(),
            moving_time=100,
            distance_km=1,
        )

        tools = ActivityMCPTools(request=_mcp_request(outsider, "activities:read"))
        with self.assertRaises(PermissionDenied):
            tools.get_activity(activity_id=activity.id)

    def test_list_activities_requires_activities_read_scope(self) -> None:
        athlete = User.objects.create_user(email="mcp-list-activities-scope@example.cc", password="x", name="Athlete")
        tools = ActivityMCPTools(request=_mcp_request(athlete, "workouts:write"))

        with self.assertRaises(PermissionDenied):
            tools.list_activities()

    def test_list_activities_returns_only_the_callers_own_activities(self) -> None:
        athlete = User.objects.create_user(email="mcp-list-activities@example.cc", password="x", name="Athlete")
        other = User.objects.create_user(email="mcp-list-activities-other@example.cc", password="x", name="Other")
        Activity.objects.create(
            id=generate_id("act"),
            athlete=athlete,
            sport="bike",
            name="Mine",
            start_date=timezone.now(),
            moving_time=100,
            distance_km=1,
        )
        Activity.objects.create(
            id=generate_id("act"),
            athlete=other,
            sport="bike",
            name="Not mine",
            start_date=timezone.now(),
            moving_time=100,
            distance_km=1,
        )

        tools = ActivityMCPTools(request=_mcp_request(athlete, "activities:read"))
        result = tools.list_activities()

        self.assertEqual(len(result["data"]), 1)
        self.assertEqual(result["data"][0]["name"], "Mine")


class AthleteToolsTests(TestCase):
    def test_get_me_returns_the_callers_own_profile(self) -> None:
        athlete = User.objects.create_user(email="mcp-me@example.cc", password="x", name="Athlete", ftp=250)
        tools = AthleteMCPTools(request=_mcp_request(athlete, "activities:read"))

        result = tools.get_me()

        self.assertEqual(result["id"], athlete.id)
        self.assertEqual(result["ftp"], 250)

    def test_get_athlete_zones_wraps_result_in_data_key(self) -> None:
        athlete = User.objects.create_user(email="mcp-zones@example.cc", password="x", name="Athlete")
        tools = AthleteMCPTools(request=_mcp_request(athlete, "activities:read"))

        result = tools.get_athlete_zones()

        self.assertEqual(len(result["data"]), 4)
        self.assertTrue(all("zones" in entry for entry in result["data"]))

    def test_list_best_efforts_rejects_an_unknown_kind(self) -> None:
        athlete = User.objects.create_user(email="mcp-best-efforts@example.cc", password="x", name="Athlete")
        tools = AthleteMCPTools(request=_mcp_request(athlete, "activities:read"))

        with self.assertRaises(ValidationError):
            tools.list_best_efforts(kind="not-a-real-kind")


class GearAndRaceToolsTests(TestCase):
    def test_list_bikes_wraps_empty_result_in_data_key(self) -> None:
        athlete = User.objects.create_user(email="mcp-bikes@example.cc", password="x", name="Athlete")
        tools = GearMCPTools(request=_mcp_request(athlete, "activities:read"))

        self.assertEqual(tools.list_bikes(), {"data": []})

    def test_list_races_requires_activities_read_scope(self) -> None:
        athlete = User.objects.create_user(email="mcp-races-scope@example.cc", password="x", name="Athlete")
        tools = RaceMCPTools(request=_mcp_request(athlete, "workouts:write"))

        with self.assertRaises(PermissionDenied):
            tools.list_races()
