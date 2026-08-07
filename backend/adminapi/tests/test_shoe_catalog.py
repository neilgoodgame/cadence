from django.test import TestCase

from accounts.models import User
from gear.models import Shoe, ShoeModel, ShoeModelVersion

from ..models import CatalogAuditLogEntry
from .helpers import _bearer_client

# The gear app ships with a seeded starter catalog (gear/migrations/0002_seed_shoe_catalog.py,
# real brands like Nike/Hoka/Saucony), present in every test DB - use a manufacturer name that
# can't collide with it, and compare counts by delta rather than absolute values.
_MANUFACTURER = "Testrunner Co"


def _version_strings(entry: dict) -> list[str]:
    return [v["version"] for v in entry["versions"]]


class AdminShoeCatalogTests(TestCase):
    def setUp(self):
        self.admin = User.objects.create_user(email="admin@example.cc", password="x", name="Admin", is_admin=True)
        self.client_ = _bearer_client(self.admin)

    def test_post_with_new_manufacturer_and_model_creates_entry_and_audit_row(self):
        before = ShoeModel.objects.count()
        response = self.client_.post(
            "/v1/admin/shoe-catalog",
            {"manufacturer": _MANUFACTURER, "model": "Speedster", "version": "4"},
            format="json",
        )
        self.assertEqual(response.status_code, 201)
        body = response.json()
        self.assertEqual(body["manufacturer"], _MANUFACTURER)
        self.assertEqual(body["versions"], [{"version": "4", "usage_count": 0}])

        self.assertEqual(ShoeModel.objects.count(), before + 1)
        self.assertEqual(CatalogAuditLogEntry.objects.count(), 1)
        entry = CatalogAuditLogEntry.objects.get()
        self.assertEqual(entry.action, "added")
        self.assertEqual(entry.by, self.admin)

    def test_post_with_existing_manufacturer_and_model_appends_version(self):
        shoe_model = ShoeModel.objects.create(manufacturer=_MANUFACTURER, model="Speedster", created_by=self.admin)
        ShoeModelVersion.objects.create(shoe_model=shoe_model, version="2")
        before = ShoeModel.objects.count()

        response = self.client_.post(
            "/v1/admin/shoe-catalog",
            {"manufacturer": _MANUFACTURER, "model": "Speedster", "version": "3"},
            format="json",
        )
        self.assertEqual(response.status_code, 201)
        self.assertEqual(sorted(_version_strings(response.json())), ["2", "3"])
        self.assertEqual(ShoeModel.objects.count(), before)

    def test_post_matches_existing_model_case_insensitively(self):
        shoe_model = ShoeModel.objects.create(manufacturer=_MANUFACTURER, model="Speedster", created_by=self.admin)
        ShoeModelVersion.objects.create(shoe_model=shoe_model, version="2")
        before = ShoeModel.objects.count()

        response = self.client_.post(
            "/v1/admin/shoe-catalog",
            {"manufacturer": _MANUFACTURER.lower(), "model": "speedster", "version": "3"},
            format="json",
        )
        self.assertEqual(response.status_code, 201)
        self.assertEqual(ShoeModel.objects.count(), before)

    def test_post_duplicate_version_returns_409_and_writes_no_audit_row(self):
        shoe_model = ShoeModel.objects.create(manufacturer=_MANUFACTURER, model="Speedster", created_by=self.admin)
        ShoeModelVersion.objects.create(shoe_model=shoe_model, version="3")

        response = self.client_.post(
            "/v1/admin/shoe-catalog",
            {"manufacturer": _MANUFACTURER, "model": "Speedster", "version": "3"},
            format="json",
        )
        self.assertEqual(response.status_code, 409)
        self.assertEqual(CatalogAuditLogEntry.objects.count(), 0)

    def test_add_version_endpoint_appends_and_logs(self):
        shoe_model = ShoeModel.objects.create(manufacturer=_MANUFACTURER, model="Trailster", created_by=self.admin)
        ShoeModelVersion.objects.create(shoe_model=shoe_model, version="2")

        response = self.client_.post(
            f"/v1/admin/shoe-catalog/{shoe_model.id}/versions", {"version": "3"}, format="json"
        )
        self.assertEqual(response.status_code, 201)
        self.assertEqual(sorted(_version_strings(response.json())), ["2", "3"])
        self.assertEqual(CatalogAuditLogEntry.objects.count(), 1)

    def test_add_version_endpoint_rejects_duplicate(self):
        shoe_model = ShoeModel.objects.create(manufacturer=_MANUFACTURER, model="Trailster", created_by=self.admin)
        ShoeModelVersion.objects.create(shoe_model=shoe_model, version="2")

        response = self.client_.post(
            f"/v1/admin/shoe-catalog/{shoe_model.id}/versions", {"version": "2"}, format="json"
        )
        self.assertEqual(response.status_code, 409)

    def test_delete_with_no_shoes_in_use_cascades_and_logs(self):
        shoe_model = ShoeModel.objects.create(manufacturer=_MANUFACTURER, model="Trackster", created_by=self.admin)
        ShoeModelVersion.objects.create(shoe_model=shoe_model, version="3")

        response = self.client_.delete(f"/v1/admin/shoe-catalog/{shoe_model.id}")
        self.assertEqual(response.status_code, 204)
        self.assertFalse(ShoeModel.objects.filter(pk=shoe_model.id).exists())
        self.assertFalse(ShoeModelVersion.objects.filter(shoe_model_id=shoe_model.id).exists())

        entry = CatalogAuditLogEntry.objects.get()
        self.assertEqual(entry.action, "removed")
        self.assertEqual(entry.description, f"{_MANUFACTURER} Trackster")

    def test_delete_blocked_when_a_shoe_references_a_version(self):
        athlete = User.objects.create_user(email="athlete@example.cc", password="x", name="Athlete")
        shoe_model = ShoeModel.objects.create(manufacturer=_MANUFACTURER, model="Trackster", created_by=self.admin)
        version = ShoeModelVersion.objects.create(shoe_model=shoe_model, version="3")
        Shoe.objects.create(athlete=athlete, shoe_model_version=version, name="Race day")

        response = self.client_.delete(f"/v1/admin/shoe-catalog/{shoe_model.id}")
        self.assertEqual(response.status_code, 409)

        self.assertTrue(ShoeModel.objects.filter(pk=shoe_model.id).exists())
        self.assertTrue(ShoeModelVersion.objects.filter(pk=version.id).exists())
        self.assertEqual(CatalogAuditLogEntry.objects.count(), 0)

    def test_versions_report_usage_count_including_retired_shoes(self):
        athlete = User.objects.create_user(email="usage-athlete@example.cc", password="x", name="Usage Athlete")
        shoe_model = ShoeModel.objects.create(manufacturer=_MANUFACTURER, model="Usester", created_by=self.admin)
        used_version = ShoeModelVersion.objects.create(shoe_model=shoe_model, version="1")
        ShoeModelVersion.objects.create(shoe_model=shoe_model, version="2")
        Shoe.objects.create(athlete=athlete, shoe_model_version=used_version, name="Daily trainer")
        Shoe.objects.create(athlete=athlete, shoe_model_version=used_version, name="Retired one", retired=True)

        response = self.client_.get(f"/v1/admin/shoe-catalog?q={_MANUFACTURER.lower()}")
        entry = next(e for e in response.json()["data"] if e["model"] == "Usester")
        by_version = {v["version"]: v["usage_count"] for v in entry["versions"]}

        self.assertEqual(by_version["1"], 2)
        self.assertEqual(by_version["2"], 0)

    def test_search_filters_case_insensitively(self):
        ShoeModel.objects.create(manufacturer=_MANUFACTURER, model="Speedster", created_by=self.admin)
        ShoeModel.objects.create(manufacturer="Zzzrunner", model="Rocket X", created_by=self.admin)

        response = self.client_.get(f"/v1/admin/shoe-catalog?q={_MANUFACTURER.lower()}")
        data = response.json()["data"]
        self.assertEqual(len(data), 1)
        self.assertEqual(data[0]["manufacturer"], _MANUFACTURER)
