from django.test import TestCase, override_settings
from django.utils import timezone
from rest_framework.test import APIClient

from accounts.models import User, UserRelationship
from activities.models import Activity, ActivityTag, Tag
from authn.jwt_utils import mint_jwt
from authn.oauth_utils import issue_token_pair

from .calculations import compute_duration_and_tss
from .models import Workout, WorkoutStep

workouts_urlconf = override_settings(ROOT_URLCONF="workouts.urls")


def _bearer_client(user, scope="activities:read activities:write workouts:write calendar:write coach gear:write"):
    access_token, _ = issue_token_pair(user, scope=scope)
    client = APIClient()
    client.credentials(HTTP_AUTHORIZATION=f"Bearer {access_token.token}")
    return client


def _delegated_client(sub, athlete_id, scopes):
    token, _claims = mint_jwt(sub=sub.id, athlete_id=athlete_id.id, scopes=scopes, expires_in=60)
    client = APIClient()
    client.credentials(HTTP_AUTHORIZATION=f"Bearer {token}")
    return client


class ComputeDurationAndTssTests(TestCase):
    def test_worked_example(self):
        steps = [{"end_type": "time", "duration": 300, "target_pct": 100, "repeat": 4}]
        duration, tss = compute_duration_and_tss(steps)
        self.assertEqual(duration, 1200)
        self.assertEqual(tss, 33)

    def test_distance_and_manual_steps_contribute_zero(self):
        steps = [
            {"end_type": "distance", "distance": 5000, "repeat": 1},
            {"end_type": "manual", "repeat": 1},
        ]
        duration, tss = compute_duration_and_tss(steps)
        self.assertEqual(duration, 0)
        self.assertEqual(tss, 0)

    def test_missing_repeat_defaults_to_one(self):
        steps = [{"end_type": "time", "duration": 60, "target_pct": 50}]
        duration, tss = compute_duration_and_tss(steps)
        self.assertEqual(duration, 60)


@workouts_urlconf
class WorkoutViewTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(email="athlete@example.cc", password="x", name="Athlete")
        self.outsider = User.objects.create_user(email="outsider@example.cc", password="x", name="Outsider")

    def _create_payload(self, **overrides):
        payload = {
            "name": "VO2 Max 5x5",
            "sport": "bike",
            "steps": [
                {"kind": "warmup", "end_type": "time", "duration": 600, "target_pct": 50, "repeat": 1},
                {"kind": "block", "end_type": "time", "duration": 300, "target_pct": 100, "repeat": 4},
                {"kind": "cool", "end_type": "time", "duration": 300, "target_pct": 40, "repeat": 1},
            ],
        }
        payload.update(overrides)
        return payload

    def test_create_workout_with_steps(self):
        response = _bearer_client(self.athlete).post("/v1/workouts", self._create_payload(), format="json")
        self.assertEqual(response.status_code, 201)
        data = response.json()
        self.assertEqual(data["name"], "VO2 Max 5x5")
        self.assertEqual(data["sport"], "bike")
        self.assertNotIn("steps", data)
        self.assertEqual(data["type"], "")
        workout = Workout.objects.get(pk=data["id"])
        self.assertEqual(workout.created_by_id, self.athlete.id)
        self.assertEqual(workout.steps.count(), 3)

    def test_create_uses_worked_example_for_duration_and_tss(self):
        payload = {
            "name": "Single block",
            "sport": "bike",
            "steps": [{"kind": "block", "end_type": "time", "duration": 300, "target_pct": 100, "repeat": 4}],
        }
        response = _bearer_client(self.athlete).post("/v1/workouts", payload, format="json")
        self.assertEqual(response.status_code, 201)
        data = response.json()
        self.assertEqual(data["duration"], 1200)
        self.assertEqual(data["tss"], 33)

    def test_create_allows_empty_steps(self):
        response = _bearer_client(self.athlete).post("/v1/workouts", self._create_payload(steps=[]), format="json")
        self.assertEqual(response.status_code, 201)
        data = response.json()
        self.assertEqual(data["duration"], 0)
        self.assertEqual(data["tss"], 0)

    def test_time_step_missing_duration_is_rejected(self):
        payload = self._create_payload(steps=[{"kind": "block", "end_type": "time", "target_pct": 100, "repeat": 1}])
        response = _bearer_client(self.athlete).post("/v1/workouts", payload, format="json")
        self.assertEqual(response.status_code, 400)

    def test_distance_step_missing_distance_is_rejected(self):
        payload = self._create_payload(steps=[{"kind": "block", "end_type": "distance", "repeat": 1}])
        response = _bearer_client(self.athlete).post("/v1/workouts", payload, format="json")
        self.assertEqual(response.status_code, 400)

    def test_list_is_scoped_to_effective_athlete(self):
        _bearer_client(self.athlete).post("/v1/workouts", self._create_payload(), format="json")
        _bearer_client(self.outsider).post(
            "/v1/workouts", self._create_payload(name="Outsider's workout"), format="json"
        )

        response = _bearer_client(self.athlete).get("/v1/workouts")
        self.assertEqual(response.status_code, 200)
        data = response.json()["data"]
        self.assertEqual(len(data), 1)
        self.assertEqual(data[0]["name"], "VO2 Max 5x5")
        self.assertNotIn("steps", data[0])

    def test_get_detail_returns_ordered_steps(self):
        create_response = _bearer_client(self.athlete).post("/v1/workouts", self._create_payload(), format="json")
        workout_id = create_response.json()["id"]

        response = _bearer_client(self.athlete).get(f"/v1/workouts/{workout_id}")
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertEqual([s["kind"] for s in data["steps"]], ["warmup", "block", "cool"])

    def test_get_missing_workout_is_404(self):
        response = _bearer_client(self.athlete).get("/v1/workouts/wkt_doesnotexist")
        self.assertEqual(response.status_code, 404)

    def test_patch_name_only_leaves_steps_untouched(self):
        create_response = _bearer_client(self.athlete).post("/v1/workouts", self._create_payload(), format="json")
        workout_id = create_response.json()["id"]

        response = _bearer_client(self.athlete).patch(f"/v1/workouts/{workout_id}", {"name": "Renamed"}, format="json")
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertEqual(data["name"], "Renamed")
        self.assertNotIn("steps", data)

        detail = _bearer_client(self.athlete).get(f"/v1/workouts/{workout_id}").json()
        self.assertEqual(len(detail["steps"]), 3)

    def test_patch_steps_replaces_list_and_recomputes(self):
        create_response = _bearer_client(self.athlete).post("/v1/workouts", self._create_payload(), format="json")
        workout_id = create_response.json()["id"]

        new_steps = [{"kind": "block", "end_type": "time", "duration": 300, "target_pct": 100, "repeat": 4}]
        response = _bearer_client(self.athlete).patch(f"/v1/workouts/{workout_id}", {"steps": new_steps}, format="json")
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertNotIn("steps", data)
        self.assertEqual(data["duration"], 1200)
        self.assertEqual(data["tss"], 33)

        detail = _bearer_client(self.athlete).get(f"/v1/workouts/{workout_id}").json()
        self.assertEqual(len(detail["steps"]), 1)

    def test_delete_cascades_steps(self):
        create_response = _bearer_client(self.athlete).post("/v1/workouts", self._create_payload(), format="json")
        workout_id = create_response.json()["id"]

        response = _bearer_client(self.athlete).delete(f"/v1/workouts/{workout_id}")
        self.assertEqual(response.status_code, 204)
        self.assertFalse(Workout.objects.filter(pk=workout_id).exists())
        self.assertFalse(WorkoutStep.objects.filter(workout_id=workout_id).exists())

    def test_outsider_without_relationship_cannot_list(self):
        response = _delegated_client(self.outsider, self.athlete, scopes=["activities:read"]).get("/v1/workouts")
        self.assertEqual(response.status_code, 403)

    def test_outsider_without_relationship_cannot_create(self):
        response = _delegated_client(self.outsider, self.athlete, scopes=["workouts:write"]).post(
            "/v1/workouts", self._create_payload(), format="json"
        )
        self.assertEqual(response.status_code, 403)

    def test_outsider_cannot_get_another_athletes_workout_by_id(self):
        create_response = _bearer_client(self.athlete).post("/v1/workouts", self._create_payload(), format="json")
        workout_id = create_response.json()["id"]

        response = _bearer_client(self.outsider).get(f"/v1/workouts/{workout_id}")
        self.assertEqual(response.status_code, 403)

    def test_viewer_can_list_and_get_via_delegated_jwt(self):
        UserRelationship.objects.create(
            owner=self.athlete,
            grantee=self.outsider,
            role=UserRelationship.ROLE_VIEWER,
            status=UserRelationship.STATUS_ACTIVE,
        )
        create_response = _bearer_client(self.athlete).post("/v1/workouts", self._create_payload(), format="json")
        workout_id = create_response.json()["id"]

        client = _delegated_client(self.outsider, self.athlete, scopes=["activities:read"])
        list_response = client.get("/v1/workouts")
        self.assertEqual(list_response.status_code, 200)
        self.assertEqual(len(list_response.json()["data"]), 1)

        detail_response = client.get(f"/v1/workouts/{workout_id}")
        self.assertEqual(detail_response.status_code, 200)

    def test_viewer_cannot_create_patch_or_delete(self):
        UserRelationship.objects.create(
            owner=self.athlete,
            grantee=self.outsider,
            role=UserRelationship.ROLE_VIEWER,
            status=UserRelationship.STATUS_ACTIVE,
        )
        client = _delegated_client(self.outsider, self.athlete, scopes=["activities:read"])

        create_response = client.post("/v1/workouts", self._create_payload(), format="json")
        self.assertEqual(create_response.status_code, 403)

        existing = _bearer_client(self.athlete).post("/v1/workouts", self._create_payload(), format="json")
        workout_id = existing.json()["id"]

        patch_response = client.patch(f"/v1/workouts/{workout_id}", {"name": "Hacked"}, format="json")
        self.assertEqual(patch_response.status_code, 403)

        delete_response = client.delete(f"/v1/workouts/{workout_id}")
        self.assertEqual(delete_response.status_code, 403)

    def test_coach_can_create_patch_and_delete(self):
        UserRelationship.objects.create(
            owner=self.athlete,
            grantee=self.outsider,
            role=UserRelationship.ROLE_COACH,
            status=UserRelationship.STATUS_ACTIVE,
        )
        client = _delegated_client(self.outsider, self.athlete, scopes=["workouts:write"])

        create_response = client.post("/v1/workouts", self._create_payload(), format="json")
        self.assertEqual(create_response.status_code, 201)
        workout_id = create_response.json()["id"]
        self.assertEqual(Workout.objects.get(pk=workout_id).created_by_id, self.athlete.id)

        patch_response = client.patch(f"/v1/workouts/{workout_id}", {"name": "Coached"}, format="json")
        self.assertEqual(patch_response.status_code, 200)
        self.assertEqual(patch_response.json()["name"], "Coached")

        delete_response = client.delete(f"/v1/workouts/{workout_id}")
        self.assertEqual(delete_response.status_code, 204)


@workouts_urlconf
class WorkoutMatchListViewTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(email="athlete@example.cc", password="x", name="Athlete")
        self.outsider = User.objects.create_user(email="outsider@example.cc", password="x", name="Outsider")
        self.workout = Workout.objects.create(
            created_by=self.athlete, name="VO2 Max 5x5", sport="bike", duration=1200, tss=33
        )

        self.auto_activity = Activity.objects.create(
            athlete=self.athlete,
            sport="bike",
            name="Auto Match",
            start_date=timezone.now(),
            moving_time=1200,
            tss=33,
            workout=self.workout,
        )
        tag = Tag.objects.create(athlete=self.athlete, name="Auto-matched", origin="auto")
        ActivityTag.objects.create(activity=self.auto_activity, tag=tag)

        self.manual_activity = Activity.objects.create(
            athlete=self.athlete,
            sport="bike",
            name="Manual Match",
            start_date=timezone.now(),
            moving_time=1000,
            tss=20,
            workout=self.workout,
        )

        Activity.objects.create(
            athlete=self.athlete, sport="bike", name="Unrelated", start_date=timezone.now(), moving_time=600
        )

    def test_lists_all_matches_by_default(self):
        response = _bearer_client(self.athlete).get(f"/v1/workouts/{self.workout.id}/matches")
        self.assertEqual(response.status_code, 200)
        data = response.json()["data"]
        self.assertEqual({m["activity_id"] for m in data}, {self.auto_activity.id, self.manual_activity.id})

    def test_auto_match_has_confidence_and_compliance(self):
        response = _bearer_client(self.athlete).get(f"/v1/workouts/{self.workout.id}/matches?method=auto")
        data = response.json()["data"]
        self.assertEqual(len(data), 1)
        self.assertEqual(data[0]["activity_id"], self.auto_activity.id)
        self.assertEqual(data[0]["method"], "auto")
        self.assertEqual(data[0]["confidence"], 1.0)
        self.assertEqual(data[0]["compliance"], 1.0)

    def test_manual_match_has_no_confidence(self):
        response = _bearer_client(self.athlete).get(f"/v1/workouts/{self.workout.id}/matches?method=manual")
        data = response.json()["data"]
        self.assertEqual(len(data), 1)
        self.assertEqual(data[0]["activity_id"], self.manual_activity.id)
        self.assertEqual(data[0]["method"], "manual")
        self.assertIsNone(data[0]["confidence"])
        self.assertEqual(data[0]["compliance"], 0.61)

    def test_invalid_method_returns_400(self):
        response = _bearer_client(self.athlete).get(f"/v1/workouts/{self.workout.id}/matches?method=bogus")
        self.assertEqual(response.status_code, 400)

    def test_outsider_forbidden(self):
        response = _bearer_client(self.outsider).get(f"/v1/workouts/{self.workout.id}/matches")
        self.assertEqual(response.status_code, 403)
