from django.test import TestCase

from accounts.models import User

from ..models import CatalogAuditLogEntry
from .helpers import _bearer_client


class AdminAuditLogTests(TestCase):
    def setUp(self):
        self.admin = User.objects.create_user(email="admin@example.cc", password="x", name="Admin", is_admin=True)
        self.client_ = _bearer_client(self.admin)

    def test_entries_ordered_newest_first(self):
        first = CatalogAuditLogEntry.objects.create(description="First", action="added", by=self.admin)
        second = CatalogAuditLogEntry.objects.create(description="Second", action="added", by=self.admin)

        response = self.client_.get("/v1/admin/audit-log")
        data = response.json()["data"]
        self.assertEqual([d["id"] for d in data], [second.id, first.id])

    def test_entry_survives_deletion_of_the_by_user(self):
        actor = User.objects.create_user(email="actor@example.cc", password="x", name="Actor")
        entry = CatalogAuditLogEntry.objects.create(description="Something", action="added", by=actor)
        actor.delete()

        response = self.client_.get("/v1/admin/audit-log")
        data = response.json()["data"]
        row = next(d for d in data if d["id"] == entry.id)
        self.assertIsNone(row["by"])
        self.assertEqual(row["description"], "Something")

    def test_a_blocked_delete_produces_no_new_row(self):
        from gear.models import Shoe, ShoeModel, ShoeModelVersion

        athlete = User.objects.create_user(email="athlete@example.cc", password="x", name="Athlete")
        shoe_model = ShoeModel.objects.create(manufacturer="Testrunner Co", model="Blocked", created_by=self.admin)
        version = ShoeModelVersion.objects.create(shoe_model=shoe_model, version="1")
        Shoe.objects.create(athlete=athlete, shoe_model_version=version, name="In use")

        before = CatalogAuditLogEntry.objects.count()
        response = self.client_.delete(f"/v1/admin/shoe-catalog/{shoe_model.id}")
        self.assertEqual(response.status_code, 409)
        self.assertEqual(CatalogAuditLogEntry.objects.count(), before)
