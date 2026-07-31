from django.test import TestCase

from accounts.models import User, UserRelationship

from ..models import ActivityComment
from .helpers import _bearer_client, _delegated_client, _make_activity


class ActivityCommentViewTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(email="athlete@example.cc", password="x", name="Athlete")
        self.coach = User.objects.create_user(email="coach@example.cc", password="x", name="Coach")
        self.viewer = User.objects.create_user(email="viewer@example.cc", password="x", name="Viewer")
        self.outsider = User.objects.create_user(email="outsider@example.cc", password="x", name="Outsider")
        UserRelationship.objects.create(
            owner=self.athlete, grantee=self.coach, role=UserRelationship.ROLE_COACH, status="active"
        )
        UserRelationship.objects.create(
            owner=self.athlete, grantee=self.viewer, role=UserRelationship.ROLE_VIEWER, status="active"
        )

    def test_athlete_can_post_and_list_own_comment(self):
        activity = _make_activity(self.athlete)
        response = _bearer_client(self.athlete).post(
            f"/v1/activities/{activity.id}/comments", {"text": "Felt great today"}, format="json"
        )
        self.assertEqual(response.status_code, 201)
        body = response.json()
        self.assertEqual(body["text"], "Felt great today")
        self.assertEqual(body["author_id"], self.athlete.id)
        self.assertEqual(body["author_role"], "athlete")

        listing = _bearer_client(self.athlete).get(f"/v1/activities/{activity.id}/comments")
        self.assertEqual(listing.status_code, 200)
        self.assertEqual(len(listing.json()["data"]), 1)

    def test_coach_comment_is_labeled_with_coach_role(self):
        activity = _make_activity(self.athlete)
        client = _delegated_client(self.coach, self.athlete, scopes=["activities:read"])
        response = client.post(f"/v1/activities/{activity.id}/comments", {"text": "Good pacing"}, format="json")
        self.assertEqual(response.status_code, 201)
        self.assertEqual(response.json()["author_role"], "coach")
        self.assertEqual(response.json()["author_id"], self.coach.id)

    def test_viewer_comment_is_labeled_with_viewer_role(self):
        activity = _make_activity(self.athlete)
        client = _delegated_client(self.viewer, self.athlete, scopes=["activities:read"])
        response = client.post(f"/v1/activities/{activity.id}/comments", {"text": "Nice work"}, format="json")
        self.assertEqual(response.status_code, 201)
        self.assertEqual(response.json()["author_role"], "viewer")

    def test_comments_are_ordered_oldest_first(self):
        activity = _make_activity(self.athlete)
        client = _bearer_client(self.athlete)
        client.post(f"/v1/activities/{activity.id}/comments", {"text": "First"}, format="json")
        client.post(f"/v1/activities/{activity.id}/comments", {"text": "Second"}, format="json")
        response = client.get(f"/v1/activities/{activity.id}/comments")
        texts = [c["text"] for c in response.json()["data"]]
        self.assertEqual(texts, ["First", "Second"])

    def test_empty_text_is_rejected(self):
        activity = _make_activity(self.athlete)
        response = _bearer_client(self.athlete).post(
            f"/v1/activities/{activity.id}/comments", {"text": "   "}, format="json"
        )
        self.assertEqual(response.status_code, 400)

    def test_outsider_without_relationship_cannot_read_or_post(self):
        activity = _make_activity(self.athlete)
        client = _delegated_client(self.outsider, self.athlete, scopes=["activities:read"])
        self.assertEqual(client.get(f"/v1/activities/{activity.id}/comments").status_code, 403)
        self.assertEqual(
            client.post(f"/v1/activities/{activity.id}/comments", {"text": "hi"}, format="json").status_code, 403
        )

    def test_author_can_delete_own_comment(self):
        activity = _make_activity(self.athlete)
        comment = ActivityComment.objects.create(activity=activity, author=self.athlete, text="Oops")
        response = _bearer_client(self.athlete).delete(f"/v1/activities/{activity.id}/comments/{comment.id}")
        self.assertEqual(response.status_code, 204)
        self.assertFalse(ActivityComment.objects.filter(pk=comment.id).exists())

    def test_cannot_delete_someone_elses_comment(self):
        activity = _make_activity(self.athlete)
        comment = ActivityComment.objects.create(activity=activity, author=self.athlete, text="Mine")
        client = _delegated_client(self.coach, self.athlete, scopes=["activities:read"])
        response = client.delete(f"/v1/activities/{activity.id}/comments/{comment.id}")
        self.assertEqual(response.status_code, 403)
        self.assertTrue(ActivityComment.objects.filter(pk=comment.id).exists())
