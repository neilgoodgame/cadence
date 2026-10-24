from types import SimpleNamespace
from typing import Any

from django.test import TestCase
from rest_framework.exceptions import PermissionDenied, ValidationError

from accounts.models import User
from authn.oauth_utils import issue_token_pair
from core.exceptions import ConflictError

from .mcp import GearMCPTools
from .models import Bike, Shoe, ShoeModel, ShoeModelVersion


def _mcp_request(user: User, scope: str) -> Any:
    access_token, _ = issue_token_pair(user, scope=scope)
    return SimpleNamespace(user=user, auth=access_token)


class CreateBikeToolTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(email="mcp-gear-bike@example.cc", password="x", name="Athlete")
        self.tools = GearMCPTools(request=_mcp_request(self.athlete, "gear:write"))

    def test_creates_bike_with_full_fields(self):
        result = self.tools.create_bike(name="Tarmac SL7", kind="road", groupset="Dura-Ace", distance_km=1200)

        self.assertEqual(result["name"], "Tarmac SL7")
        self.assertEqual(result["kind"], "road")
        self.assertEqual(result["groupset"], "Dura-Ace")
        self.assertEqual(result["distance_km"], 1200)
        self.assertEqual(result["athlete_id"], self.athlete.id)

    def test_creates_bike_with_defaults(self):
        result = self.tools.create_bike(name="Stumpjumper")

        self.assertEqual(result["kind"], "")
        self.assertEqual(result["distance_km"], 0)
        self.assertEqual(result["components"], 0)

    def test_requires_write_scope(self):
        tools = GearMCPTools(request=_mcp_request(self.athlete, "activities:read"))
        with self.assertRaises(PermissionDenied):
            tools.create_bike(name="Tarmac SL7")


class UpdateBikeToolTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(email="mcp-gear-bike-u@example.cc", password="x", name="Athlete")
        self.tools = GearMCPTools(request=_mcp_request(self.athlete, "gear:write"))
        self.bike = Bike.objects.create(athlete=self.athlete, name="Original", kind="road")

    def test_updates_only_passed_fields(self):
        result = self.tools.update_bike(bike_id=self.bike.id, name="Renamed")

        self.assertEqual(result["name"], "Renamed")
        self.assertEqual(result["kind"], "road")

    def test_updates_kind_and_distance(self):
        result = self.tools.update_bike(bike_id=self.bike.id, kind="gravel", distance_km=500)

        self.assertEqual(result["kind"], "gravel")
        self.assertEqual(result["distance_km"], 500)

    def test_requires_write_scope(self):
        tools = GearMCPTools(request=_mcp_request(self.athlete, "activities:read"))
        with self.assertRaises(PermissionDenied):
            tools.update_bike(bike_id=self.bike.id, name="Hijacked")

    def test_outsider_cannot_update_another_athletes_bike(self):
        outsider = User.objects.create_user(email="mcp-gear-bike-outsider@example.cc", password="x", name="Outsider")
        tools = GearMCPTools(request=_mcp_request(outsider, "gear:write"))
        with self.assertRaises(PermissionDenied):
            tools.update_bike(bike_id=self.bike.id, name="Hijacked")


class DeleteBikeToolTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(email="mcp-gear-bike-d@example.cc", password="x", name="Athlete")
        self.tools = GearMCPTools(request=_mcp_request(self.athlete, "gear:write"))
        self.bike = Bike.objects.create(athlete=self.athlete, name="Doomed")

    def test_deletes_the_bike(self):
        result = self.tools.delete_bike(bike_id=self.bike.id)

        self.assertEqual(result, {"id": self.bike.id, "deleted": True})
        self.assertFalse(Bike.objects.filter(pk=self.bike.id).exists())

    def test_requires_write_scope(self):
        tools = GearMCPTools(request=_mcp_request(self.athlete, "activities:read"))
        with self.assertRaises(PermissionDenied):
            tools.delete_bike(bike_id=self.bike.id)

    def test_outsider_cannot_delete_another_athletes_bike(self):
        outsider = User.objects.create_user(email="mcp-gear-bike-d-outsider@example.cc", password="x", name="Outsider")
        tools = GearMCPTools(request=_mcp_request(outsider, "gear:write"))
        with self.assertRaises(PermissionDenied):
            tools.delete_bike(bike_id=self.bike.id)
        self.assertTrue(Bike.objects.filter(pk=self.bike.id).exists())


class SearchShoeCatalogToolTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(email="mcp-gear-catalog@example.cc", password="x", name="Athlete")
        self.tools = GearMCPTools(request=_mcp_request(self.athlete, "activities:read"))
        # Fake, distinctive manufacturer names - the real shoe catalog is seeded with real brands
        # by a data migration, so a query/count test must avoid colliding with (or depending on
        # the exact size of) that pre-existing seeded data.
        self.zzyzx = ShoeModel.objects.create(manufacturer="Zzyzx", model="Vaporfly")
        asics = ShoeModel.objects.create(manufacturer="Qorvex", model="Nimbus")
        self.zzyzx_version = ShoeModelVersion.objects.create(shoe_model=self.zzyzx, version="3")
        ShoeModelVersion.objects.create(shoe_model=asics, version="25")

    def test_search_filters_by_manufacturer_or_model(self):
        result = self.tools.search_shoe_catalog(q="zzyzx")

        self.assertEqual(len(result["data"]), 1)
        self.assertEqual(result["data"][0]["manufacturer"], "Zzyzx")

    def test_no_query_lists_whole_catalog(self):
        result = self.tools.search_shoe_catalog()

        ids = {row["shoe_model_version_id"] for row in result["data"]}
        self.assertIn(self.zzyzx_version.id, ids)


class CreateShoeToolTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(email="mcp-gear-shoe@example.cc", password="x", name="Athlete")
        self.tools = GearMCPTools(request=_mcp_request(self.athlete, "gear:write"))
        shoe_model = ShoeModel.objects.create(manufacturer="Nike", model="Vaporfly")
        self.version = ShoeModelVersion.objects.create(shoe_model=shoe_model, version="3")

    def test_creates_shoe_with_default_name(self):
        result = self.tools.create_shoe(shoe_model_version_id=self.version.id, colourway="Volt / Black")

        self.assertEqual(result["name"], "Nike Vaporfly 3 Volt / Black")
        self.assertEqual(result["colourway"], "Volt / Black")

    def test_creates_shoe_with_explicit_name_and_limit(self):
        result = self.tools.create_shoe(
            shoe_model_version_id=self.version.id, colourway="Volt", name="Race Day Flyers", limit_km=500
        )

        self.assertEqual(result["name"], "Race Day Flyers")
        self.assertEqual(result["limit_km"], 500)

    def test_duplicate_name_is_rejected(self):
        self.tools.create_shoe(shoe_model_version_id=self.version.id, colourway="Volt", name="Flyers")
        with self.assertRaises(ConflictError):
            self.tools.create_shoe(shoe_model_version_id=self.version.id, colourway="Pink", name="Flyers")

    def test_requires_write_scope(self):
        tools = GearMCPTools(request=_mcp_request(self.athlete, "activities:read"))
        with self.assertRaises(PermissionDenied):
            tools.create_shoe(shoe_model_version_id=self.version.id, colourway="Volt")


class UpdateShoeToolTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(email="mcp-gear-shoe-u@example.cc", password="x", name="Athlete")
        self.tools = GearMCPTools(request=_mcp_request(self.athlete, "gear:write"))
        shoe_model = ShoeModel.objects.create(manufacturer="Nike", model="Vaporfly")
        version = ShoeModelVersion.objects.create(shoe_model=shoe_model, version="3")
        self.shoe = Shoe.objects.create(
            athlete=self.athlete, shoe_model_version=version, colourway="Volt", name="Flyers", limit_km=600
        )

    def test_updates_only_passed_fields(self):
        result = self.tools.update_shoe(shoe_id=self.shoe.id, km=250)

        self.assertEqual(result["km"], 250)
        self.assertEqual(result["name"], "Flyers")

    def test_retiring_a_shoe(self):
        self.tools.update_shoe(shoe_id=self.shoe.id, retired=True)
        self.shoe.refresh_from_db()
        self.assertTrue(self.shoe.retired)

    def test_requires_write_scope(self):
        tools = GearMCPTools(request=_mcp_request(self.athlete, "activities:read"))
        with self.assertRaises(PermissionDenied):
            tools.update_shoe(shoe_id=self.shoe.id, km=250)

    def test_outsider_cannot_update_another_athletes_shoe(self):
        outsider = User.objects.create_user(email="mcp-gear-shoe-outsider@example.cc", password="x", name="Outsider")
        tools = GearMCPTools(request=_mcp_request(outsider, "gear:write"))
        with self.assertRaises(PermissionDenied):
            tools.update_shoe(shoe_id=self.shoe.id, km=250)


class DeleteShoeToolTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(email="mcp-gear-shoe-d@example.cc", password="x", name="Athlete")
        self.tools = GearMCPTools(request=_mcp_request(self.athlete, "gear:write"))
        shoe_model = ShoeModel.objects.create(manufacturer="Nike", model="Vaporfly")
        version = ShoeModelVersion.objects.create(shoe_model=shoe_model, version="3")
        self.shoe = Shoe.objects.create(athlete=self.athlete, shoe_model_version=version, name="Doomed")

    def test_deletes_the_shoe(self):
        result = self.tools.delete_shoe(shoe_id=self.shoe.id)

        self.assertEqual(result, {"id": self.shoe.id, "deleted": True})
        self.assertFalse(Shoe.objects.filter(pk=self.shoe.id).exists())

    def test_requires_write_scope(self):
        tools = GearMCPTools(request=_mcp_request(self.athlete, "activities:read"))
        with self.assertRaises(PermissionDenied):
            tools.delete_shoe(shoe_id=self.shoe.id)

    def test_outsider_cannot_delete_another_athletes_shoe(self):
        outsider = User.objects.create_user(email="mcp-gear-shoe-d-outsider@example.cc", password="x", name="Outsider")
        tools = GearMCPTools(request=_mcp_request(outsider, "gear:write"))
        with self.assertRaises(PermissionDenied):
            tools.delete_shoe(shoe_id=self.shoe.id)
        self.assertTrue(Shoe.objects.filter(pk=self.shoe.id).exists())


class CreateShoeCatalogEntryToolTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(email="mcp-gear-catalog-c@example.cc", password="x", name="Athlete")
        self.tools = GearMCPTools(request=_mcp_request(self.athlete, "gear:write"))

    def test_creates_catalog_entry(self):
        result = self.tools.create_shoe_catalog_entry(manufacturer="Hoka", model="Rocket X", version="2")

        self.assertEqual(result["manufacturer"], "Hoka")
        self.assertEqual(result["model"], "Rocket X")
        self.assertEqual(result["version"], "2")
        self.assertTrue(ShoeModel.objects.filter(manufacturer="Hoka", model="Rocket X").exists())

    def test_blank_manufacturer_is_rejected(self):
        with self.assertRaises(ValidationError):
            self.tools.create_shoe_catalog_entry(manufacturer="  ", model="Rocket X")

    def test_requires_write_scope(self):
        tools = GearMCPTools(request=_mcp_request(self.athlete, "activities:read"))
        with self.assertRaises(PermissionDenied):
            tools.create_shoe_catalog_entry(manufacturer="Hoka", model="Rocket X")
