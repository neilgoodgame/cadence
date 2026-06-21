from django.test import TestCase

from accounts.models import User

from ..models import ActivityTag, Tag
from .helpers import _bearer_client, _delegated_client, _make_activity


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
