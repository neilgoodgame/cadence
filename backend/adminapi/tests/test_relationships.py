from django.test import TestCase

from accounts.models import User, UserRelationship

from ..models import CatalogAuditLogEntry
from .helpers import _bearer_client


class AdminRelationshipTests(TestCase):
    def setUp(self):
        self.admin = User.objects.create_user(email="admin@example.cc", password="x", name="Admin", is_admin=True)
        self.client_ = _bearer_client(self.admin)
        self.coach = User.objects.create_user(email="coach@example.cc", password="x", name="Coach")
        self.athlete = User.objects.create_user(email="athlete@example.cc", password="x", name="Athlete")

    def test_list_spans_all_owners_not_just_the_admin(self):
        UserRelationship.objects.create(
            owner=self.athlete,
            grantee=self.coach,
            role=UserRelationship.ROLE_COACH,
            status=UserRelationship.STATUS_ACTIVE,
        )
        response = self.client_.get("/v1/admin/relationships")
        self.assertEqual(response.status_code, 200)
        data = response.json()["data"]
        self.assertEqual(len(data), 1)
        self.assertEqual(data[0]["coach_name"], "Coach")
        self.assertEqual(data[0]["athlete_name"], "Athlete")
        self.assertEqual(data[0]["role"], "coach")

    def test_revoke_succeeds_on_a_relationship_the_admin_does_not_own(self):
        relationship = UserRelationship.objects.create(
            owner=self.athlete,
            grantee=self.coach,
            role=UserRelationship.ROLE_COACH,
            status=UserRelationship.STATUS_ACTIVE,
        )
        response = self.client_.delete(f"/v1/admin/relationships/{relationship.id}")
        self.assertEqual(response.status_code, 204)
        self.assertFalse(UserRelationship.objects.filter(pk=relationship.id).exists())

    def test_revoke_unknown_id_returns_404(self):
        response = self.client_.delete("/v1/admin/relationships/rel_doesnotexist")
        self.assertEqual(response.status_code, 404)

    def test_revoke_writes_no_audit_row(self):
        relationship = UserRelationship.objects.create(
            owner=self.athlete,
            grantee=self.coach,
            role=UserRelationship.ROLE_COACH,
            status=UserRelationship.STATUS_ACTIVE,
        )
        self.client_.delete(f"/v1/admin/relationships/{relationship.id}")
        self.assertEqual(CatalogAuditLogEntry.objects.count(), 0)
