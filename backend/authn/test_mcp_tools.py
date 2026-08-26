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

from accounts.models import PersonalAccessToken, User, UserRelationship
from accounts.tokens import generate_secret, hash_secret, visible_prefix
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

    def test_create_workout_rejects_a_fraction_passed_instead_of_a_percentage(self) -> None:
        athlete = User.objects.create_user(email="mcp-create-workout-fraction@example.cc", password="x", name="Athlete")
        tools = WorkoutMCPTools(request=_mcp_request(athlete, "workouts:write"))

        # The exact mistake seen live: 0.65 instead of 65 for 65% of threshold.
        with self.assertRaises(ValidationError):
            tools.create_workout(
                name="Bad",
                sport="bike",
                steps=[
                    {
                        "kind": "block",
                        "end_type": "time",
                        "duration": 100,
                        "target_type": "power",
                        "target_low": 0.65,
                        "target_high": 0.75,
                    }
                ],
            )

    def test_create_workout_rejects_a_fraction_nested_in_a_repeat_group(self) -> None:
        athlete = User.objects.create_user(
            email="mcp-create-workout-fraction-nested@example.cc", password="x", name="Athlete"
        )
        tools = WorkoutMCPTools(request=_mcp_request(athlete, "workouts:write"))

        with self.assertRaises(ValidationError):
            tools.create_workout(
                name="Bad",
                sport="bike",
                steps=[
                    {
                        "kind": "repeat",
                        "repeat": 3,
                        "children": [
                            {
                                "kind": "block",
                                "end_type": "time",
                                "duration": 100,
                                "target_type": "power",
                                "target_low": 0.95,
                                "target_high": 0.95,
                            }
                        ],
                    }
                ],
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

    def test_schedule_workout_accepts_notes(self) -> None:
        athlete = User.objects.create_user(email="mcp-schedule-notes@example.cc", password="x", name="Athlete")
        workout = Workout.objects.create(created_by=athlete, name="W", sport="bike")
        tools = SchedulingMCPTools(request=_mcp_request(athlete, "calendar:write"))

        result = tools.schedule_workout(workout_id=workout.id, date="2026-09-01", notes="Race sim")

        self.assertEqual(result["notes"], "Race sim")

    # Regression test mirroring backend_java's McpToolsIntegrationTest -
    # moveWorkoutChangesTheDateOfAnExistingEntryInPlace: Claude.ai's MCP connector had no way to
    # move a scheduled workout, so a requested "swap two dates" left duplicate entries instead of
    # a clean move.
    def test_move_workout_changes_date_and_time_of_day_in_place(self) -> None:
        athlete = User.objects.create_user(email="mcp-move@example.cc", password="x", name="Athlete")
        workout = Workout.objects.create(created_by=athlete, name="W", sport="bike")
        tools = SchedulingMCPTools(request=_mcp_request(athlete, "calendar:write"))
        scheduled = tools.schedule_workout(workout_id=workout.id, date="2026-09-06", time_of_day="am")

        moved = tools.move_workout(scheduled_workout_id=scheduled["id"], date="2026-09-13", time_of_day="pm")

        self.assertEqual(moved["id"], scheduled["id"])
        self.assertEqual(moved["date"], "2026-09-13")
        self.assertEqual(moved["time_of_day"], "PM")

    def test_move_workout_can_update_just_the_notes(self) -> None:
        athlete = User.objects.create_user(email="mcp-move-notes@example.cc", password="x", name="Athlete")
        workout = Workout.objects.create(created_by=athlete, name="W", sport="bike")
        tools = SchedulingMCPTools(request=_mcp_request(athlete, "calendar:write"))
        scheduled = tools.schedule_workout(workout_id=workout.id, date="2026-09-06")

        annotated = tools.move_workout(scheduled_workout_id=scheduled["id"], notes="Swap if it rains")

        self.assertEqual(annotated["date"], "2026-09-06")
        self.assertEqual(annotated["notes"], "Swap if it rains")

    def test_unschedule_workout_removes_the_entry(self) -> None:
        athlete = User.objects.create_user(email="mcp-unschedule@example.cc", password="x", name="Athlete")
        workout = Workout.objects.create(created_by=athlete, name="W", sport="bike")
        tools = SchedulingMCPTools(request=_mcp_request(athlete, "calendar:write"))
        scheduled = tools.schedule_workout(workout_id=workout.id, date="2026-09-06")

        result = tools.unschedule_workout(scheduled_workout_id=scheduled["id"])

        self.assertEqual(result, {"deleted": True, "id": scheduled["id"]})
        self.assertFalse(ScheduledWorkout.objects.filter(pk=scheduled["id"]).exists())

    def test_unschedule_workout_refuses_a_completed_entry(self) -> None:
        athlete = User.objects.create_user(email="mcp-unschedule-completed@example.cc", password="x", name="Athlete")
        workout = Workout.objects.create(created_by=athlete, name="W", sport="bike")
        activity = Activity.objects.create(
            id=generate_id("act"),
            athlete=athlete,
            sport="bike",
            name="Completed ride",
            start_date=timezone.datetime(2026, 9, 6, tzinfo=timezone.get_default_timezone()),
            moving_time=1800,
            distance_km=10,
        )
        scheduled = ScheduledWorkout.objects.create(
            workout=workout, athlete=athlete, date="2026-09-06", activity=activity, status="completed"
        )
        tools = SchedulingMCPTools(request=_mcp_request(athlete, "calendar:write"))

        with self.assertRaises(ValidationError):
            tools.unschedule_workout(scheduled_workout_id=scheduled.id)

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

    def _new_activity(self, athlete: User) -> Activity:
        return Activity.objects.create(
            id=generate_id("act"),
            athlete=athlete,
            sport="run",
            name="Morning Run",
            start_date=timezone.now(),
            moving_time=100,
            distance_km=1,
        )

    def test_post_activity_comment_attributes_it_to_the_real_caller(self) -> None:
        athlete = User.objects.create_user(email="mcp-comment-athlete@example.cc", password="x", name="Athlete")
        activity = self._new_activity(athlete)
        tools = ActivityMCPTools(request=_mcp_request(athlete, "activities:read activities:write"))

        result = tools.post_activity_comment(activity_id=activity.id, text="Nice pace today!")

        self.assertEqual(result["text"], "Nice pace today!")
        self.assertEqual(result["author_id"], athlete.id)
        self.assertEqual(result["author_role"], "athlete")

    def test_post_activity_comment_requires_the_activities_write_scope(self) -> None:
        athlete = User.objects.create_user(email="mcp-comment-scope-athlete@example.cc", password="x", name="Athlete")
        activity = self._new_activity(athlete)
        tools = ActivityMCPTools(request=_mcp_request(athlete, "activities:read"))

        with self.assertRaises(PermissionDenied):
            tools.post_activity_comment(activity_id=activity.id, text="Should fail")

    def test_post_activity_comment_rejects_blank_or_overlong_text(self) -> None:
        athlete = User.objects.create_user(
            email="mcp-comment-validation-athlete@example.cc", password="x", name="Athlete"
        )
        activity = self._new_activity(athlete)
        tools = ActivityMCPTools(request=_mcp_request(athlete, "activities:read activities:write"))

        with self.assertRaises(ValidationError):
            tools.post_activity_comment(activity_id=activity.id, text="  ")
        with self.assertRaises(ValidationError):
            tools.post_activity_comment(activity_id=activity.id, text="x" * 4001)

    def test_post_activity_comment_rejects_an_outsider_with_no_share(self) -> None:
        athlete = User.objects.create_user(
            email="mcp-comment-outsider-athlete@example.cc", password="x", name="Athlete"
        )
        outsider = User.objects.create_user(email="mcp-comment-outsider@example.cc", password="x", name="Outsider")
        activity = self._new_activity(athlete)
        tools = ActivityMCPTools(request=_mcp_request(outsider, "activities:read activities:write"))

        with self.assertRaises(PermissionDenied):
            tools.post_activity_comment(activity_id=activity.id, text="Should fail")

    def test_list_activity_comments_returns_them_oldest_first(self) -> None:
        athlete = User.objects.create_user(email="mcp-list-comments-athlete@example.cc", password="x", name="Athlete")
        activity = self._new_activity(athlete)
        write_tools = ActivityMCPTools(request=_mcp_request(athlete, "activities:read activities:write"))
        write_tools.post_activity_comment(activity_id=activity.id, text="First")
        write_tools.post_activity_comment(activity_id=activity.id, text="Second")

        tools = ActivityMCPTools(request=_mcp_request(athlete, "activities:read"))
        result = tools.list_activity_comments(activity_id=activity.id)

        self.assertEqual(len(result["data"]), 2)
        self.assertEqual(result["data"][0]["text"], "First")
        self.assertEqual(result["data"][1]["text"], "Second")

    def test_list_activity_comments_requires_the_activities_read_scope(self) -> None:
        athlete = User.objects.create_user(
            email="mcp-list-comments-scope-athlete@example.cc", password="x", name="Athlete"
        )
        activity = self._new_activity(athlete)
        tools = ActivityMCPTools(request=_mcp_request(athlete, "workouts:write"))

        with self.assertRaises(PermissionDenied):
            tools.list_activity_comments(activity_id=activity.id)

    def test_list_activity_comments_rejects_an_outsider_with_no_share(self) -> None:
        athlete = User.objects.create_user(
            email="mcp-list-comments-outsider-athlete@example.cc", password="x", name="Athlete"
        )
        outsider = User.objects.create_user(
            email="mcp-list-comments-outsider@example.cc", password="x", name="Outsider"
        )
        activity = self._new_activity(athlete)
        tools = ActivityMCPTools(request=_mcp_request(outsider, "activities:read"))

        with self.assertRaises(PermissionDenied):
            tools.list_activity_comments(activity_id=activity.id)


class AthleteToolsTests(TestCase):
    def test_get_me_returns_the_callers_own_profile(self) -> None:
        athlete = User.objects.create_user(email="mcp-me@example.cc", password="x", name="Athlete", ftp=250)
        tools = AthleteMCPTools(request=_mcp_request(athlete, "activities:read"))

        result = tools.get_me()

        self.assertEqual(result["id"], athlete.id)
        self.assertEqual(result["ftp"], 250)

    def test_get_me_for_a_delegated_coach_returns_the_athletes_profile_not_the_coachs_own(self) -> None:
        athlete = User.objects.create_user(email="mcp-me-athlete@example.cc", password="x", name="Athlete", ftp=250)
        coach = User.objects.create_user(email="mcp-me-coach@example.cc", password="x", name="Coach")
        UserRelationship.objects.create(
            owner=athlete, grantee=coach, role=UserRelationship.ROLE_COACH, status=UserRelationship.STATUS_ACTIVE
        )
        secret = generate_secret()
        pat = PersonalAccessToken.objects.create(
            user=coach,
            name="Delegated",
            prefix=visible_prefix(secret),
            hashed_secret=hash_secret(secret),
            scopes=["activities:read"],
            delegated_athlete=athlete,
        )
        tools = AthleteMCPTools(request=SimpleNamespace(user=coach, auth=pat))

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
