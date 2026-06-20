from django.test import TestCase

from accounts.models import User, UserRelationship

from ..models import ActivityTag, Tag
from .helpers import _bearer_client, _delegated_client, _make_activity


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
