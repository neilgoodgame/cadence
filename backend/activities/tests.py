from datetime import datetime, timedelta, timezone

from django.test import TestCase
from rest_framework.test import APIClient

from accounts.models import User, UserRelationship
from authn.jwt_utils import mint_jwt
from authn.oauth_utils import issue_token_pair
from workouts.models import Workout

from .models import Activity, ActivityTag, DurationCurve, Lap, Record, Tag


def _bearer_client(user, scope="activities:read activities:write coach"):
    access_token, _ = issue_token_pair(user, scope=scope)
    client = APIClient()
    client.credentials(HTTP_AUTHORIZATION=f"Bearer {access_token.token}")
    return client


def _delegated_client(sub, athlete_id, scopes):
    token, _claims = mint_jwt(sub=sub.id, athlete_id=athlete_id.id, scopes=scopes, expires_in=60)
    client = APIClient()
    client.credentials(HTTP_AUTHORIZATION=f"Bearer {token}")
    return client


def _make_activity(athlete, **kwargs):
    defaults = {
        "sport": "run",
        "environment": "outdoor",
        "name": "Morning Run",
        "start_date": datetime(2026, 1, 1, 7, 0, tzinfo=timezone.utc),
        "moving_time": 1800,
        "distance_km": 5.0,
        "tss": 50,
        "avg_hr": 140,
        "max_hr": 160,
    }
    defaults.update(kwargs)
    return Activity.objects.create(athlete=athlete, **defaults)


class ActivityListViewTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(email="athlete@example.cc", password="x", name="Athlete")
        self.other_athlete = User.objects.create_user(email="other@example.cc", password="x", name="Other")
        self.outsider = User.objects.create_user(email="outsider@example.cc", password="x", name="Outsider")

    def test_list_scoped_to_effective_athlete(self):
        _make_activity(self.athlete, name="Mine")
        _make_activity(self.other_athlete, name="Not mine")

        response = _bearer_client(self.athlete).get("/v1/activities")
        self.assertEqual(response.status_code, 200)
        body = response.json()
        self.assertEqual(body["object"], "list")
        self.assertIn("has_more", body)
        self.assertIn("next_cursor", body)
        names = [a["name"] for a in body["data"]]
        self.assertEqual(names, ["Mine"])

    def test_activity_shape_includes_tags_as_names(self):
        activity = _make_activity(self.athlete)
        tag = Tag.objects.create(athlete=self.athlete, name="Race")
        ActivityTag.objects.create(activity=activity, tag=tag)

        response = _bearer_client(self.athlete).get("/v1/activities")
        self.assertEqual(response.json()["data"][0]["tags"], ["Race"])

    def test_filter_by_sport_query_param(self):
        _make_activity(self.athlete, sport="run", name="Run")
        _make_activity(self.athlete, sport="bike", name="Ride")

        response = _bearer_client(self.athlete).get("/v1/activities?sport=bike")
        names = [a["name"] for a in response.json()["data"]]
        self.assertEqual(names, ["Ride"])

    def test_unknown_sport_query_param_400(self):
        response = _bearer_client(self.athlete).get("/v1/activities?sport=skiing")
        self.assertEqual(response.status_code, 400)

    def test_filter_by_environment_query_param(self):
        _make_activity(self.athlete, environment="indoor", name="Treadmill")
        _make_activity(self.athlete, environment="outdoor", name="Trail")

        response = _bearer_client(self.athlete).get("/v1/activities?environment=indoor")
        names = [a["name"] for a in response.json()["data"]]
        self.assertEqual(names, ["Treadmill"])

    def test_cql_numeric_filter(self):
        _make_activity(self.athlete, name="Easy", avg_hr=120)
        _make_activity(self.athlete, name="Hard", avg_hr=160)

        response = _bearer_client(self.athlete).get("/v1/activities?q=" + "hr>140bpm".replace(">", "%3E"))
        names = [a["name"] for a in response.json()["data"]]
        self.assertEqual(names, ["Hard"])

    def test_cql_tag_filter(self):
        tagged = _make_activity(self.athlete, name="Tagged")
        _make_activity(self.athlete, name="Untagged")
        tag = Tag.objects.create(athlete=self.athlete, name="Race")
        ActivityTag.objects.create(activity=tagged, tag=tag)

        response = _bearer_client(self.athlete).get("/v1/activities?q=tag%20Race")
        names = [a["name"] for a in response.json()["data"]]
        self.assertEqual(names, ["Tagged"])

    def test_cql_and_or_combination(self):
        _make_activity(self.athlete, name="RunHigh", sport="run", avg_hr=160)
        _make_activity(self.athlete, name="RunLow", sport="run", avg_hr=120)
        _make_activity(self.athlete, name="BikeHigh", sport="bike", avg_hr=160)

        q = "run and hr>140bpm".replace(">", "%3E").replace(" ", "%20")
        response = _bearer_client(self.athlete).get(f"/v1/activities?q={q}")
        names = [a["name"] for a in response.json()["data"]]
        self.assertEqual(names, ["RunHigh"])

    def test_malformed_cql_returns_400(self):
        response = _bearer_client(self.athlete).get("/v1/activities?q=" + ">".replace(">", "%3E"))
        self.assertEqual(response.status_code, 400)
        self.assertEqual(response.json()["error"]["type"], "invalid_request_error")

    def test_cql_order_by_overrides_sort_param(self):
        _make_activity(self.athlete, name="Low", tss=10)
        _make_activity(self.athlete, name="High", tss=90)

        response = _bearer_client(self.athlete).get(
            "/v1/activities?q=order%20by%20tss%20desc&sort=tss"
        )
        names = [a["name"] for a in response.json()["data"]]
        self.assertEqual(names, ["High", "Low"])

    def test_sort_param_ascending_and_descending(self):
        _make_activity(self.athlete, name="Low", tss=10)
        _make_activity(self.athlete, name="High", tss=90)

        desc = _bearer_client(self.athlete).get("/v1/activities?sort=-tss")
        self.assertEqual([a["name"] for a in desc.json()["data"]], ["High", "Low"])

        asc = _bearer_client(self.athlete).get("/v1/activities?sort=tss")
        self.assertEqual([a["name"] for a in asc.json()["data"]], ["Low", "High"])

    def test_unknown_sort_field_400(self):
        response = _bearer_client(self.athlete).get("/v1/activities?sort=bogus")
        self.assertEqual(response.status_code, 400)

    def test_outsider_without_relationship_forbidden(self):
        client = _delegated_client(self.outsider, self.athlete, scopes=["activities:read"])
        self.assertEqual(client.get("/v1/activities").status_code, 403)

    def test_viewer_can_list(self):
        UserRelationship.objects.create(
            owner=self.athlete, grantee=self.outsider, role=UserRelationship.ROLE_VIEWER, status=UserRelationship.STATUS_ACTIVE
        )
        _make_activity(self.athlete)
        client = _delegated_client(self.outsider, self.athlete, scopes=["activities:read"])
        self.assertEqual(client.get("/v1/activities").status_code, 200)


class ActivityDetailViewTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(email="athlete@example.cc", password="x", name="Athlete")
        self.outsider = User.objects.create_user(email="outsider@example.cc", password="x", name="Outsider")

    def test_get_detail(self):
        activity = _make_activity(self.athlete)
        response = _bearer_client(self.athlete).get(f"/v1/activities/{activity.id}")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["id"], activity.id)

    def test_get_detail_not_found(self):
        response = _bearer_client(self.athlete).get("/v1/activities/act_doesnotexist")
        self.assertEqual(response.status_code, 404)

    def test_outsider_forbidden_on_detail(self):
        activity = _make_activity(self.athlete)
        client = _delegated_client(self.outsider, self.athlete, scopes=["activities:read"])
        response = client.get(f"/v1/activities/{activity.id}")
        self.assertEqual(response.status_code, 403)

    def test_patch_rename_and_sport(self):
        activity = _make_activity(self.athlete)
        response = _bearer_client(self.athlete).patch(
            f"/v1/activities/{activity.id}", {"name": "Renamed", "sport": "bike"}, format="json"
        )
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["name"], "Renamed")
        self.assertEqual(response.json()["sport"], "bike")

    def test_patch_link_workout(self):
        activity = _make_activity(self.athlete)
        workout = Workout.objects.create(created_by=self.athlete, name="Z2 long ride", sport="bike")
        response = _bearer_client(self.athlete).patch(
            f"/v1/activities/{activity.id}", {"workout_id": workout.id}, format="json"
        )
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["workout_id"], workout.id)

    def test_patch_link_workout_belonging_to_someone_else_404(self):
        activity = _make_activity(self.athlete)
        other_athlete = User.objects.create_user(email="other2@example.cc", password="x", name="Other2")
        workout = Workout.objects.create(created_by=other_athlete, name="Not mine", sport="bike")
        response = _bearer_client(self.athlete).patch(
            f"/v1/activities/{activity.id}", {"workout_id": workout.id}, format="json"
        )
        self.assertEqual(response.status_code, 404)

    def test_patch_unlink_workout(self):
        workout = Workout.objects.create(created_by=self.athlete, name="Z2 long ride", sport="bike")
        activity = _make_activity(self.athlete, workout=workout)
        response = _bearer_client(self.athlete).patch(
            f"/v1/activities/{activity.id}", {"workout_id": None}, format="json"
        )
        self.assertEqual(response.status_code, 200)
        self.assertIsNone(response.json()["workout_id"])

    def test_patch_weights_and_fluids(self):
        activity = _make_activity(self.athlete)
        response = _bearer_client(self.athlete).patch(
            f"/v1/activities/{activity.id}",
            {"start_weight_kg": 72.5, "end_weight_kg": 71.0, "fluids_ml": 750},
            format="json",
        )
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertEqual(data["start_weight_kg"], 72.5)
        self.assertEqual(data["end_weight_kg"], 71.0)
        self.assertEqual(data["fluids_ml"], 750)

    def test_viewer_cannot_patch(self):
        UserRelationship.objects.create(
            owner=self.athlete, grantee=self.outsider, role=UserRelationship.ROLE_VIEWER, status=UserRelationship.STATUS_ACTIVE
        )
        activity = _make_activity(self.athlete)
        client = _delegated_client(self.outsider, self.athlete, scopes=["activities:read"])
        response = client.patch(f"/v1/activities/{activity.id}", {"name": "Nope"}, format="json")
        self.assertEqual(response.status_code, 403)

    def test_coach_can_patch(self):
        UserRelationship.objects.create(
            owner=self.athlete, grantee=self.outsider, role=UserRelationship.ROLE_COACH, status=UserRelationship.STATUS_ACTIVE
        )
        activity = _make_activity(self.athlete)
        client = _delegated_client(self.outsider, self.athlete, scopes=["coach"])
        response = client.patch(f"/v1/activities/{activity.id}", {"name": "Coached edit"}, format="json")
        self.assertEqual(response.status_code, 200)

    def test_delete_activity_cascades_laps_and_tags(self):
        activity = _make_activity(self.athlete)
        Lap.objects.create(activity=activity, index=1, duration=300, distance_km=1.0)
        tag = Tag.objects.create(athlete=self.athlete, name="Race")
        ActivityTag.objects.create(activity=activity, tag=tag)

        response = _bearer_client(self.athlete).delete(f"/v1/activities/{activity.id}")
        self.assertEqual(response.status_code, 204)
        self.assertFalse(Activity.objects.filter(pk=activity.id).exists())
        self.assertFalse(Lap.objects.filter(activity_id=activity.id).exists())
        self.assertFalse(ActivityTag.objects.filter(activity_id=activity.id).exists())
        # The tag itself (athlete-owned, possibly used elsewhere) survives.
        self.assertTrue(Tag.objects.filter(pk=tag.id).exists())

    def test_outsider_cannot_delete(self):
        activity = _make_activity(self.athlete)
        client = _delegated_client(self.outsider, self.athlete, scopes=["activities:read"])
        response = client.delete(f"/v1/activities/{activity.id}")
        self.assertEqual(response.status_code, 403)


class LapListViewTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(email="athlete@example.cc", password="x", name="Athlete")
        self.outsider = User.objects.create_user(email="outsider@example.cc", password="x", name="Outsider")

    def test_lists_laps_in_order(self):
        activity = _make_activity(self.athlete)
        Lap.objects.create(activity=activity, index=2, duration=300, distance_km=1.0)
        Lap.objects.create(activity=activity, index=1, duration=290, distance_km=1.0)

        response = _bearer_client(self.athlete).get(f"/v1/activities/{activity.id}/laps")
        self.assertEqual(response.status_code, 200)
        indexes = [lap["index"] for lap in response.json()["data"]]
        self.assertEqual(indexes, [1, 2])

    def test_outsider_forbidden(self):
        activity = _make_activity(self.athlete)
        client = _delegated_client(self.outsider, self.athlete, scopes=["activities:read"])
        response = client.get(f"/v1/activities/{activity.id}/laps")
        self.assertEqual(response.status_code, 403)


class TagViewTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(email="athlete@example.cc", password="x", name="Athlete")
        self.other_athlete = User.objects.create_user(email="other@example.cc", password="x", name="Other")
        self.outsider = User.objects.create_user(email="outsider@example.cc", password="x", name="Outsider")

    def test_list_tags_scoped_to_athlete(self):
        Tag.objects.create(athlete=self.athlete, name="Race")
        Tag.objects.create(athlete=self.other_athlete, name="Not mine")

        response = _bearer_client(self.athlete).get("/v1/tags")
        self.assertEqual(response.status_code, 200)
        names = [t["name"] for t in response.json()["data"]]
        self.assertEqual(names, ["Race"])

    def test_attach_existing_tag_by_id(self):
        activity = _make_activity(self.athlete)
        tag = Tag.objects.create(athlete=self.athlete, name="Race")

        response = _bearer_client(self.athlete).post(
            f"/v1/activities/{activity.id}/tags", {"tag_id": tag.id}, format="json"
        )
        self.assertEqual(response.status_code, 201)
        body = response.json()
        self.assertEqual(body["activity_id"], activity.id)
        self.assertEqual(body["tag"]["name"], "Race")
        self.assertTrue(ActivityTag.objects.filter(activity=activity, tag=tag).exists())

    def test_attach_new_tag_by_name_creates_manual_tag(self):
        activity = _make_activity(self.athlete)
        response = _bearer_client(self.athlete).post(
            f"/v1/activities/{activity.id}/tags", {"name": "Long Run"}, format="json"
        )
        self.assertEqual(response.status_code, 201)
        tag = Tag.objects.get(athlete=self.athlete, name="Long Run")
        self.assertEqual(tag.origin, "manual")

    def test_attach_is_idempotent(self):
        activity = _make_activity(self.athlete)
        tag = Tag.objects.create(athlete=self.athlete, name="Race")
        client = _bearer_client(self.athlete)
        client.post(f"/v1/activities/{activity.id}/tags", {"tag_id": tag.id}, format="json")
        client.post(f"/v1/activities/{activity.id}/tags", {"tag_id": tag.id}, format="json")
        self.assertEqual(ActivityTag.objects.filter(activity=activity, tag=tag).count(), 1)

    def test_attach_requires_tag_id_or_name(self):
        activity = _make_activity(self.athlete)
        response = _bearer_client(self.athlete).post(f"/v1/activities/{activity.id}/tags", {}, format="json")
        self.assertEqual(response.status_code, 400)

    def test_remove_manual_tag(self):
        activity = _make_activity(self.athlete)
        tag = Tag.objects.create(athlete=self.athlete, name="Race")
        ActivityTag.objects.create(activity=activity, tag=tag)

        response = _bearer_client(self.athlete).delete(f"/v1/activities/{activity.id}/tags/{tag.id}")
        self.assertEqual(response.status_code, 204)
        self.assertFalse(ActivityTag.objects.filter(activity=activity, tag=tag).exists())

    def test_remove_auto_tag_rejected(self):
        activity = _make_activity(self.athlete)
        tag = Tag.objects.create(athlete=self.athlete, name="Interval Match", origin="auto")
        ActivityTag.objects.create(activity=activity, tag=tag)

        response = _bearer_client(self.athlete).delete(f"/v1/activities/{activity.id}/tags/{tag.id}")
        self.assertEqual(response.status_code, 403)
        self.assertTrue(ActivityTag.objects.filter(activity=activity, tag=tag).exists())

    def test_outsider_cannot_attach_or_remove_tags(self):
        activity = _make_activity(self.athlete)
        tag = Tag.objects.create(athlete=self.athlete, name="Race")
        client = _delegated_client(self.outsider, self.athlete, scopes=["activities:read"])
        self.assertEqual(
            client.post(f"/v1/activities/{activity.id}/tags", {"tag_id": tag.id}, format="json").status_code, 403
        )
        ActivityTag.objects.create(activity=activity, tag=tag)
        self.assertEqual(client.delete(f"/v1/activities/{activity.id}/tags/{tag.id}").status_code, 403)


class StreamsViewTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(email="athlete@example.cc", password="x", name="Athlete")
        self.outsider = User.objects.create_user(email="outsider@example.cc", password="x", name="Outsider")
        self.activity = _make_activity(self.athlete, has_gps=True)
        for i in range(5):
            Record.objects.create(
                activity=self.activity,
                t=i,
                ts=self.activity.start_date + timedelta(seconds=i),
                power=200 + i,
                heartrate=140 + i,
                lat=37.0 + i * 0.001,
                lng=-122.0 - i * 0.001,
            )

    def test_default_fields_include_latlng_when_gps(self):
        response = _bearer_client(self.athlete).get(f"/v1/activities/{self.activity.id}/streams")
        self.assertEqual(response.status_code, 200)
        body = response.json()
        self.assertEqual(body["object"], "streams")
        self.assertEqual(body["resolution"], "high")
        self.assertEqual(body["fields"]["time"], [0, 1, 2, 3, 4])
        self.assertEqual(body["fields"]["power"], [200, 201, 202, 203, 204])
        self.assertEqual(body["fields"]["latlng"][0], [37.0, -122.0])

    def test_indoor_activity_omits_latlng_by_default(self):
        indoor = _make_activity(self.athlete, has_gps=False, name="Indoor")
        Record.objects.create(activity=indoor, t=0, ts=indoor.start_date, power=200)

        response = _bearer_client(self.athlete).get(f"/v1/activities/{indoor.id}/streams")
        self.assertNotIn("latlng", response.json()["fields"])

    def test_fields_param_limits_channels(self):
        response = _bearer_client(self.athlete).get(f"/v1/activities/{self.activity.id}/streams?fields=power")
        self.assertEqual(set(response.json()["fields"]), {"power"})

    def test_unknown_field_400(self):
        response = _bearer_client(self.athlete).get(f"/v1/activities/{self.activity.id}/streams?fields=bogus")
        self.assertEqual(response.status_code, 400)

    def test_unknown_resolution_400(self):
        response = _bearer_client(self.athlete).get(f"/v1/activities/{self.activity.id}/streams?resolution=ultra")
        self.assertEqual(response.status_code, 400)

    def test_low_resolution_decimates(self):
        response = _bearer_client(self.athlete).get(
            f"/v1/activities/{self.activity.id}/streams?resolution=medium&fields=time"
        )
        self.assertEqual(response.json()["fields"]["time"], [0])

    def test_outsider_forbidden(self):
        client = _delegated_client(self.outsider, self.athlete, scopes=["activities:read"])
        response = client.get(f"/v1/activities/{self.activity.id}/streams")
        self.assertEqual(response.status_code, 403)


class CurvesViewTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(email="athlete@example.cc", password="x", name="Athlete")
        self.outsider = User.objects.create_user(email="outsider@example.cc", password="x", name="Outsider")
        self.activity = _make_activity(self.athlete)
        DurationCurve.objects.create(
            activity=self.activity, metric="power", extends_to=3600, points={"5": 400, "60": 300}
        )

    def test_get_power_curve(self):
        response = _bearer_client(self.athlete).get(f"/v1/activities/{self.activity.id}/curves")
        self.assertEqual(response.status_code, 200)
        body = response.json()
        self.assertEqual(body["metric"], "power")
        self.assertEqual(body["extends_to"], 3600)
        self.assertEqual(body["points"], {"5": 400, "60": 300})

    def test_missing_metric_curve_returns_empty(self):
        response = _bearer_client(self.athlete).get(f"/v1/activities/{self.activity.id}/curves?metric=heartrate")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json(), {"metric": "heartrate", "extends_to": 0, "points": {}})

    def test_unknown_metric_400(self):
        response = _bearer_client(self.athlete).get(f"/v1/activities/{self.activity.id}/curves?metric=bogus")
        self.assertEqual(response.status_code, 400)

    def test_outsider_forbidden(self):
        client = _delegated_client(self.outsider, self.athlete, scopes=["activities:read"])
        response = client.get(f"/v1/activities/{self.activity.id}/curves")
        self.assertEqual(response.status_code, 403)
