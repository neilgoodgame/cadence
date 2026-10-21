from types import SimpleNamespace
from typing import Any

from django.test import TestCase
from rest_framework.exceptions import PermissionDenied, ValidationError

from accounts.models import User
from authn.oauth_utils import issue_token_pair

from .mcp import WorkoutMCPTools
from .models import Workout, WorkoutFolder


def _mcp_request(user: User, scope: str) -> Any:
    access_token, _ = issue_token_pair(user, scope=scope)
    return SimpleNamespace(user=user, auth=access_token)


SIMPLE_STEPS = [
    {
        "kind": "repeat",
        "repeat": 4,
        "children": [
            {
                "kind": "block",
                "end_type": "time",
                "duration": 300,
                "target_type": "power",
                "target_low": 100,
                "target_high": 100,
            }
        ],
    }
]


class UpdateWorkoutToolTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(email="mcp-workout@example.cc", password="x", name="Athlete")
        self.tools = WorkoutMCPTools(request=_mcp_request(self.athlete, "workouts:write"))
        self.workout = Workout.objects.create(created_by=self.athlete, name="Original Name", sport="bike", tags=["a"])

    def test_name_only_update_leaves_steps_and_tags_untouched(self):
        result = self.tools.update_workout(workout_id=self.workout.id, name="Renamed")

        self.assertEqual(result["name"], "Renamed")
        self.assertEqual(result["tags"], ["a"])
        self.workout.refresh_from_db()
        self.assertEqual(self.workout.steps.count(), 0)

    def test_steps_update_replaces_tree_and_recomputes_duration_and_tss(self):
        result = self.tools.update_workout(workout_id=self.workout.id, steps=SIMPLE_STEPS)

        self.assertEqual(result["duration"], 1200)
        self.assertEqual(result["tss"], 33)

    def test_blank_name_is_rejected(self):
        with self.assertRaises(ValidationError):
            self.tools.update_workout(workout_id=self.workout.id, name="   ")

    def test_target_fraction_scale_is_rejected(self):
        bad_steps = [
            {
                "kind": "block",
                "end_type": "time",
                "duration": 300,
                "target_type": "power",
                "target_low": 0.65,
                "target_high": 0.65,
            }
        ]
        with self.assertRaises(ValidationError):
            self.tools.update_workout(workout_id=self.workout.id, steps=bad_steps)

    def test_folder_id_sets_then_empty_string_clears_it(self):
        folder = WorkoutFolder.objects.create(created_by=self.athlete, name="Block A")

        result = self.tools.update_workout(workout_id=self.workout.id, folder_id=folder.id)
        self.assertEqual(result["folder_id"], folder.id)

        result = self.tools.update_workout(workout_id=self.workout.id, folder_id="")
        self.assertIsNone(result["folder_id"])

    def test_omitting_folder_id_leaves_it_untouched(self):
        folder = WorkoutFolder.objects.create(created_by=self.athlete, name="Block A")
        self.tools.update_workout(workout_id=self.workout.id, folder_id=folder.id)

        result = self.tools.update_workout(workout_id=self.workout.id, name="Renamed Again")
        self.assertEqual(result["folder_id"], folder.id)

    def test_tags_replace_whole_list(self):
        result = self.tools.update_workout(workout_id=self.workout.id, tags=["b", "c"])
        self.assertEqual(result["tags"], ["b", "c"])

    def test_requires_write_scope(self):
        tools = WorkoutMCPTools(request=_mcp_request(self.athlete, "activities:read"))
        with self.assertRaises(PermissionDenied):
            tools.update_workout(workout_id=self.workout.id, name="Renamed")

    def test_outsider_cannot_update_another_athletes_workout(self):
        outsider = User.objects.create_user(email="mcp-outsider@example.cc", password="x", name="Outsider")
        tools = WorkoutMCPTools(request=_mcp_request(outsider, "workouts:write"))
        with self.assertRaises(PermissionDenied):
            tools.update_workout(workout_id=self.workout.id, name="Hijacked")


class DeleteWorkoutToolTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(email="mcp-delete@example.cc", password="x", name="Athlete")
        self.tools = WorkoutMCPTools(request=_mcp_request(self.athlete, "workouts:write"))
        self.workout = Workout.objects.create(created_by=self.athlete, name="Doomed", sport="run")

    def test_deletes_the_workout(self):
        result = self.tools.delete_workout(workout_id=self.workout.id)

        self.assertEqual(result, {"id": self.workout.id, "deleted": True})
        self.assertFalse(Workout.objects.filter(pk=self.workout.id).exists())

    def test_requires_write_scope(self):
        tools = WorkoutMCPTools(request=_mcp_request(self.athlete, "activities:read"))
        with self.assertRaises(PermissionDenied):
            tools.delete_workout(workout_id=self.workout.id)

    def test_outsider_cannot_delete_another_athletes_workout(self):
        outsider = User.objects.create_user(email="mcp-delete-outsider@example.cc", password="x", name="Outsider")
        tools = WorkoutMCPTools(request=_mcp_request(outsider, "workouts:write"))
        with self.assertRaises(PermissionDenied):
            tools.delete_workout(workout_id=self.workout.id)
        self.assertTrue(Workout.objects.filter(pk=self.workout.id).exists())
