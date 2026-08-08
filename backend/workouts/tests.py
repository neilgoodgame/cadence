from django.test import TestCase, override_settings
from django.utils import timezone
from rest_framework.test import APIClient

from accounts.models import User, UserRelationship
from activities.models import Activity, ActivityTag, Lap, Tag
from authn.jwt_utils import mint_jwt
from authn.oauth_utils import issue_token_pair

from .calculations import compute_duration_and_tss
from .inference import Group, LeafCandidate, _compress_pass, infer_workout
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
    def test_worked_example_repeat_group(self):
        steps = [
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
        duration, tss = compute_duration_and_tss(steps)
        self.assertEqual(duration, 1200)
        self.assertEqual(tss, 33)

    def test_distance_and_manual_steps_contribute_zero_duration(self):
        steps = [
            {
                "kind": "block",
                "end_type": "distance",
                "distance": 5000,
                "target_type": "power",
                "target_low": 100,
                "target_high": 100,
            },
            {"kind": "block", "end_type": "manual", "target_type": "open"},
        ]
        duration, tss = compute_duration_and_tss(steps)
        self.assertEqual(duration, 0)
        self.assertEqual(tss, 0)

    def test_flat_leaf_step(self):
        steps = [
            {
                "kind": "block",
                "end_type": "time",
                "duration": 60,
                "target_type": "power",
                "target_low": 50,
                "target_high": 50,
            }
        ]
        duration, _tss = compute_duration_and_tss(steps)
        self.assertEqual(duration, 60)

    def test_ramp_uses_low_high_midpoint(self):
        steps = [
            {
                "kind": "block",
                "end_type": "time",
                "duration": 3600,
                "target_type": "power",
                "target_low": 50,
                "target_high": 70,
            }
        ]
        duration, tss = compute_duration_and_tss(steps)
        self.assertEqual(duration, 3600)
        self.assertEqual(tss, 36)  # midpoint 60% FTP -> (60/100)^2 * 100

    def test_nested_repeat_groups_multiply_and_sum(self):
        steps = [
            {
                "kind": "repeat",
                "repeat": 2,
                "children": [
                    {
                        "kind": "repeat",
                        "repeat": 4,
                        "children": [
                            {
                                "kind": "block",
                                "end_type": "time",
                                "duration": 240,
                                "target_type": "power",
                                "target_low": 100,
                                "target_high": 100,
                            }
                        ],
                    },
                    {
                        "kind": "rec",
                        "end_type": "time",
                        "duration": 200,
                        "target_type": "power",
                        "target_low": 50,
                        "target_high": 50,
                    },
                ],
            }
        ]
        duration, tss = compute_duration_and_tss(steps)
        self.assertEqual(duration, 2 * (4 * 240 + 200))
        self.assertEqual(tss, 56)


class CompressPassTests(TestCase):
    """Unit tests for the pure repeat-pattern-detection helpers, no DB involved."""

    def test_detects_a_flat_repeated_work_rest_pattern(self):
        leaves = [LeafCandidate(300, "power", 118), LeafCandidate(180, "power", 55)] * 3
        [group] = _compress_pass(leaves)
        self.assertIsInstance(group, Group)
        self.assertEqual(group.repeat, 3)
        self.assertEqual([(c.duration, c.pct) for c in group.children], [(300, 118), (180, 55)])

    def test_tolerates_minor_jitter_between_reps(self):
        # Real recordings never repeat exactly - duration/power drift a little rep to rep.
        leaves = [
            LeafCandidate(300, "power", 118),
            LeafCandidate(180, "power", 55),
            LeafCandidate(305, "power", 120),
            LeafCandidate(178, "power", 53),
            LeafCandidate(298, "power", 116),
            LeafCandidate(182, "power", 56),
        ]
        [group] = _compress_pass(leaves)
        self.assertIsInstance(group, Group)
        self.assertEqual(group.repeat, 3)

    def test_no_repetition_stays_flat(self):
        leaves = [
            LeafCandidate(600, "power", 55),
            LeafCandidate(1200, "power", 88),
            LeafCandidate(300, "power", 40),
        ]
        result = _compress_pass(leaves)
        self.assertEqual(result, leaves)

    def test_recompresses_two_equivalent_groups_into_an_outer_group(self):
        # Simulates what a second compression pass sees after an earlier pass has
        # already collapsed two separate inner sets into Groups.
        inner_a = Group(repeat=4, children=[LeafCandidate(240, "power", 100), LeafCandidate(60, "power", 50)])
        inner_b = Group(repeat=4, children=[LeafCandidate(241, "power", 101), LeafCandidate(59, "power", 51)])
        [outer] = _compress_pass([inner_a, inner_b])
        self.assertIsInstance(outer, Group)
        self.assertEqual(outer.repeat, 2)
        self.assertEqual(outer.children, [inner_a])

    def test_requires_at_least_two_repetitions(self):
        leaves = [LeafCandidate(300, "power", 118), LeafCandidate(180, "power", 55), LeafCandidate(400, "power", 90)]
        result = _compress_pass(leaves)
        self.assertEqual(result, leaves)


class InferWorkoutTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(email="infer@example.cc", password="x", name="Athlete", ftp=265)

    def _activity_with_laps(self, laps: list[dict], sport: str = "bike") -> Activity:
        activity = Activity.objects.create(
            athlete=self.athlete,
            sport=sport,
            name="Zwift VO2 Max 5x5",
            start_date=timezone.now(),
            moving_time=sum(lap["duration"] for lap in laps),
        )
        for i, lap in enumerate(laps):
            Lap.objects.create(
                activity=activity,
                index=i,
                duration=lap["duration"],
                distance_km=0,
                **{k: v for k, v in lap.items() if k != "duration"},
            )
        return activity

    def test_infers_warmup_repeat_group_and_cooldown(self):
        laps = [
            {"duration": 600, "avg_power": 145},  # ~55% FTP -> warmup
            {"duration": 300, "avg_power": 313},  # ~118% FTP -> work
            {"duration": 180, "avg_power": 146},  # ~55% -> rest
            {"duration": 300, "avg_power": 315},
            {"duration": 180, "avg_power": 143},
            {"duration": 300, "avg_power": 312},
            {"duration": 180, "avg_power": 145},
            {"duration": 300, "avg_power": 106},  # ~40% -> cool
        ]
        activity = self._activity_with_laps(laps)

        result = infer_workout(activity, auto_detect_repeats=True)

        self.assertEqual(result["sport"], "bike")
        kinds = [s["kind"] for s in result["steps"]]
        self.assertEqual(kinds, ["warmup", "repeat", "cool"])
        group = result["steps"][1]
        self.assertEqual(group["repeat"], 3)
        self.assertEqual([c["kind"] for c in group["children"]], ["block", "rec"])
        self.assertEqual(group["children"][0]["target_low"], 118)

    def test_auto_detect_repeats_false_stays_flat(self):
        laps = [{"duration": 300, "avg_power": 313}] * 4
        activity = self._activity_with_laps(laps)

        result = infer_workout(activity, auto_detect_repeats=False)

        self.assertEqual(len(result["steps"]), 4)
        self.assertTrue(all(s["kind"] != "repeat" for s in result["steps"]))

    def test_output_is_a_valid_step_tree_for_compute_duration_and_tss(self):
        laps = [
            {"duration": 600, "avg_power": 145},
            {"duration": 300, "avg_power": 313},
            {"duration": 180, "avg_power": 146},
            {"duration": 300, "avg_power": 315},
            {"duration": 180, "avg_power": 143},
        ]
        activity = self._activity_with_laps(laps)

        result = infer_workout(activity, auto_detect_repeats=True)
        duration, _tss = compute_duration_and_tss(result["steps"])

        self.assertEqual(duration, sum(lap["duration"] for lap in laps))

    def test_falls_back_to_heart_rate_when_no_power(self):
        self.athlete.ftp = None
        self.athlete.lthr = 165
        self.athlete.save(update_fields=["ftp", "lthr"])
        activity = self._activity_with_laps([{"duration": 600, "avg_hr": 132}], sport="run")

        result = infer_workout(activity, auto_detect_repeats=True)

        self.assertEqual(result["steps"][0]["target_type"], "hr")
        self.assertEqual(result["steps"][0]["target_low"], 80)

    def test_open_target_when_no_power_or_hr_data(self):
        activity = self._activity_with_laps([{"duration": 600}])

        result = infer_workout(activity, auto_detect_repeats=True)

        self.assertEqual(result["steps"][0]["target_type"], "open")


def _workout_payload(**overrides):
    payload = {
        "name": "VO2 Max 5x5",
        "sport": "bike",
        "steps": [
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
            },
            {
                "kind": "cool",
                "end_type": "time",
                "duration": 300,
                "target_type": "power",
                "target_low": 40,
                "target_high": 40,
            },
        ],
    }
    payload.update(overrides)
    return payload


@workouts_urlconf
class WorkoutViewTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(email="athlete@example.cc", password="x", name="Athlete")
        self.outsider = User.objects.create_user(email="outsider@example.cc", password="x", name="Outsider")

    def _create_payload(self, **overrides):
        return _workout_payload(**overrides)

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
        # warmup + repeat group + its 1 child + cool = 4 rows
        self.assertEqual(workout.steps.count(), 4)

    def test_create_uses_worked_example_for_duration_and_tss(self):
        payload = {
            "name": "Single block",
            "sport": "bike",
            "steps": [
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
            ],
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
        payload = self._create_payload(
            steps=[{"kind": "block", "end_type": "time", "target_type": "power", "target_low": 100, "target_high": 100}]
        )
        response = _bearer_client(self.athlete).post("/v1/workouts", payload, format="json")
        self.assertEqual(response.status_code, 400)

    def test_distance_step_missing_distance_is_rejected(self):
        payload = self._create_payload(
            steps=[
                {"kind": "block", "end_type": "distance", "target_type": "power", "target_low": 100, "target_high": 100}
            ]
        )
        response = _bearer_client(self.athlete).post("/v1/workouts", payload, format="json")
        self.assertEqual(response.status_code, 400)

    def test_repeat_group_without_children_is_rejected(self):
        payload = self._create_payload(steps=[{"kind": "repeat", "repeat": 3, "children": []}])
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

    def test_get_detail_returns_ordered_nested_steps(self):
        create_response = _bearer_client(self.athlete).post("/v1/workouts", self._create_payload(), format="json")
        workout_id = create_response.json()["id"]

        response = _bearer_client(self.athlete).get(f"/v1/workouts/{workout_id}")
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertEqual([s["kind"] for s in data["steps"]], ["warmup", "repeat", "cool"])
        repeat_group = data["steps"][1]
        self.assertEqual(repeat_group["repeat"], 4)
        self.assertEqual([c["kind"] for c in repeat_group["children"]], ["block"])

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

        new_steps = [
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
class WorkoutLibraryTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(email="athlete@example.cc", password="x", name="Athlete")
        self.outsider = User.objects.create_user(email="outsider@example.cc", password="x", name="Outsider")
        self.client = _bearer_client(self.athlete)

    def test_create_and_list_folders_with_counts(self):
        create_response = self.client.post("/v1/workout-folders", {"name": "VO2 Max"}, format="json")
        self.assertEqual(create_response.status_code, 201)
        folder = create_response.json()
        self.assertEqual(folder["name"], "VO2 Max")
        self.assertEqual(folder["count"], 0)

        self.client.post("/v1/workouts", _workout_payload(folder_id=folder["id"]), format="json")

        list_response = self.client.get("/v1/workout-folders")
        self.assertEqual(list_response.status_code, 200)
        data = list_response.json()["data"]
        self.assertEqual(len(data), 1)
        self.assertEqual(data[0]["count"], 1)

    def test_duplicate_folder_name_is_rejected(self):
        self.client.post("/v1/workout-folders", {"name": "Race Prep"}, format="json")
        response = self.client.post("/v1/workout-folders", {"name": "Race Prep"}, format="json")
        self.assertEqual(response.status_code, 400)

    def test_rename_and_delete_folder_unassigns_workouts(self):
        folder = self.client.post("/v1/workout-folders", {"name": "Base"}, format="json").json()
        workout = self.client.post("/v1/workouts", _workout_payload(folder_id=folder["id"]), format="json").json()
        self.assertEqual(workout["folder_id"], folder["id"])

        rename_response = self.client.patch(
            f"/v1/workout-folders/{folder['id']}", {"name": "Base / Endurance"}, format="json"
        )
        self.assertEqual(rename_response.status_code, 200)
        self.assertEqual(rename_response.json()["name"], "Base / Endurance")

        delete_response = self.client.delete(f"/v1/workout-folders/{folder['id']}")
        self.assertEqual(delete_response.status_code, 204)

        detail = self.client.get(f"/v1/workouts/{workout['id']}").json()
        self.assertIsNone(detail["folder_id"])

    def test_outsider_cannot_create_or_modify_athletes_folders(self):
        folder = self.client.post("/v1/workout-folders", {"name": "Threshold"}, format="json").json()
        outsider_client = _bearer_client(self.outsider)

        list_response = outsider_client.get("/v1/workout-folders")
        self.assertEqual(list_response.json()["data"], [])

        rename_response = outsider_client.patch(
            f"/v1/workout-folders/{folder['id']}", {"name": "Hijacked"}, format="json"
        )
        self.assertEqual(rename_response.status_code, 403)

    def test_create_and_patch_workout_with_tags(self):
        response = self.client.post("/v1/workouts", _workout_payload(tags=["intervals", "race prep"]), format="json")
        self.assertEqual(response.status_code, 201)
        self.assertEqual(response.json()["tags"], ["intervals", "race prep"])
        workout_id = response.json()["id"]

        patch_response = self.client.patch(f"/v1/workouts/{workout_id}", {"tags": ["base"]}, format="json")
        self.assertEqual(patch_response.status_code, 200)
        self.assertEqual(patch_response.json()["tags"], ["base"])

    def test_chart_preview_is_computed_on_save(self):
        response = self.client.post("/v1/workouts", _workout_payload(), format="json")
        preview = response.json()["chart_preview"]
        # warmup (50), 4x block (100), cool (40) — flattened/unrolled per leaf
        self.assertEqual(preview, [50, 100, 100, 100, 100, 40])

    def test_list_filters_by_folder_tag_sport_and_search(self):
        folder = self.client.post("/v1/workout-folders", {"name": "Intervals"}, format="json").json()
        self.client.post(
            "/v1/workouts", _workout_payload(name="VO2 Max 5x5", folder_id=folder["id"], tags=["hard"]), format="json"
        )
        self.client.post("/v1/workouts", _workout_payload(name="Easy Spin", sport="bike", tags=["easy"]), format="json")
        self.client.post("/v1/workouts", _workout_payload(name="Long Run", sport="run", tags=["easy"]), format="json")

        by_folder = self.client.get(f"/v1/workouts?folder_id={folder['id']}").json()["data"]
        self.assertEqual([w["name"] for w in by_folder], ["VO2 Max 5x5"])

        by_tag = self.client.get("/v1/workouts?tag=easy").json()["data"]
        self.assertEqual({w["name"] for w in by_tag}, {"Easy Spin", "Long Run"})

        by_sport = self.client.get("/v1/workouts?sport=run").json()["data"]
        self.assertEqual([w["name"] for w in by_sport], ["Long Run"])

        by_search = self.client.get("/v1/workouts?search=vo2").json()["data"]
        self.assertEqual([w["name"] for w in by_search], ["VO2 Max 5x5"])

    def test_list_sorts_by_name_duration_tss_and_used(self):
        from scheduling.models import ScheduledWorkout

        self.client.post("/v1/workouts", _workout_payload(name="Zulu", steps=[]), format="json")
        alpha = self.client.post("/v1/workouts", _workout_payload(name="Alpha", steps=[]), format="json").json()

        by_name = self.client.get("/v1/workouts?sort=name").json()["data"]
        self.assertEqual([w["name"] for w in by_name], ["Alpha", "Zulu"])

        # Created directly via the ORM (not the /v1/scheduled-workouts API) since this
        # test's urlconf is overridden to only mount workouts.urls.
        ScheduledWorkout.objects.create(workout_id=alpha["id"], athlete=self.athlete, date="2026-08-01")

        by_used = self.client.get("/v1/workouts?sort=used").json()["data"]
        self.assertEqual(by_used[0]["name"], "Alpha")

    def test_invalid_sort_is_rejected(self):
        response = self.client.get("/v1/workouts?sort=bogus")
        self.assertEqual(response.status_code, 400)


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
            distance_km=35.0,
            avg_power=231,
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
        self.assertEqual(data[0]["tss"], 33)
        self.assertEqual(data[0]["moving_time"], 1200)
        self.assertEqual(data[0]["distance_km"], 35.0)
        self.assertEqual(data[0]["avg_power"], 231)

    def test_manual_match_has_no_confidence(self):
        response = _bearer_client(self.athlete).get(f"/v1/workouts/{self.workout.id}/matches?method=manual")
        data = response.json()["data"]
        self.assertEqual(len(data), 1)
        self.assertEqual(data[0]["activity_id"], self.manual_activity.id)
        self.assertEqual(data[0]["method"], "manual")
        self.assertIsNone(data[0]["confidence"])
        self.assertEqual(data[0]["compliance"], 0.61)
        self.assertIsNone(data[0]["avg_power"])

    def test_invalid_method_returns_400(self):
        response = _bearer_client(self.athlete).get(f"/v1/workouts/{self.workout.id}/matches?method=bogus")
        self.assertEqual(response.status_code, 400)

    def test_outsider_forbidden(self):
        response = _bearer_client(self.outsider).get(f"/v1/workouts/{self.workout.id}/matches")
        self.assertEqual(response.status_code, 403)
