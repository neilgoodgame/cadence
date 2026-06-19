from django.test import TestCase, override_settings
from rest_framework.test import APIClient

from accounts.models import User, UserRelationship
from authn.jwt_utils import mint_jwt
from authn.oauth_utils import issue_token_pair

from .models import Bike, Component, Shoe, ShoeModel, ShoeModelVersion

# config.urls is wired up by a concurrent process; until that lands, point the
# test client straight at this app's own urlconf so these tests are self-contained.
gear_urlconf = override_settings(ROOT_URLCONF="gear.urls")


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


@gear_urlconf
class BikeViewTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(email="athlete@example.cc", password="x", name="Athlete")
        self.other_athlete = User.objects.create_user(email="other@example.cc", password="x", name="Other")
        self.outsider = User.objects.create_user(email="outsider@example.cc", password="x", name="Outsider")

    def test_create_bike(self):
        response = _bearer_client(self.athlete).post(
            "/v1/gear/bikes",
            {"name": "Tarmac SL7", "kind": "road", "groupset": "Dura-Ace", "distance_km": 1200},
            format="json",
        )
        self.assertEqual(response.status_code, 201)
        data = response.json()
        self.assertEqual(data["name"], "Tarmac SL7")
        self.assertEqual(data["athlete_id"], self.athlete.id)
        self.assertEqual(data["distance_km"], 1200)
        self.assertEqual(data["hours"], 0)
        self.assertEqual(data["rides"], 0)

    def test_create_bike_defaults_distance_to_zero(self):
        response = _bearer_client(self.athlete).post("/v1/gear/bikes", {"name": "Stumpjumper"}, format="json")
        self.assertEqual(response.status_code, 201)
        self.assertEqual(response.json()["distance_km"], 0)

    def test_list_scoped_to_effective_athlete(self):
        Bike.objects.create(athlete=self.athlete, name="Tarmac")
        Bike.objects.create(athlete=self.other_athlete, name="Roubaix")

        response = _bearer_client(self.athlete).get("/v1/gear/bikes")
        self.assertEqual(response.status_code, 200)
        names = [b["name"] for b in response.json()["data"]]
        self.assertEqual(names, ["Tarmac"])

    def test_get_detail_includes_nested_components(self):
        bike = Bike.objects.create(athlete=self.athlete, name="Tarmac")
        Component.objects.create(bike=bike, name="Chain", limit_km=4000, km=100)

        response = _bearer_client(self.athlete).get(f"/v1/gear/bikes/{bike.id}")
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertEqual(len(data["components"]), 1)
        self.assertEqual(data["components"][0]["name"], "Chain")
        self.assertEqual(data["components"][0]["bike_id"], bike.id)

    def test_get_detail_not_found(self):
        response = _bearer_client(self.athlete).get("/v1/gear/bikes/bike_doesnotexist")
        self.assertEqual(response.status_code, 404)

    def test_patch_bike(self):
        bike = Bike.objects.create(athlete=self.athlete, name="Tarmac", distance_km=100)
        response = _bearer_client(self.athlete).patch(
            f"/v1/gear/bikes/{bike.id}", {"distance_km": 500}, format="json"
        )
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["distance_km"], 500)
        bike.refresh_from_db()
        self.assertEqual(bike.distance_km, 500)

    def test_delete_bike_cascades_components(self):
        bike = Bike.objects.create(athlete=self.athlete, name="Tarmac")
        component = Component.objects.create(bike=bike, name="Chain", limit_km=4000)

        response = _bearer_client(self.athlete).delete(f"/v1/gear/bikes/{bike.id}")
        self.assertEqual(response.status_code, 204)
        self.assertFalse(Bike.objects.filter(pk=bike.id).exists())
        self.assertFalse(Component.objects.filter(pk=component.id).exists())

    def test_outsider_without_relationship_forbidden_on_list_and_create(self):
        client = _delegated_client(self.outsider, self.athlete, scopes=["activities:read"])
        self.assertEqual(client.get("/v1/gear/bikes").status_code, 403)
        response = client.post("/v1/gear/bikes", {"name": "New bike"}, format="json")
        self.assertEqual(response.status_code, 403)

    def test_viewer_cannot_write_bike(self):
        UserRelationship.objects.create(
            owner=self.athlete, grantee=self.outsider, role=UserRelationship.ROLE_VIEWER, status=UserRelationship.STATUS_ACTIVE
        )
        bike = Bike.objects.create(athlete=self.athlete, name="Tarmac")
        client = _delegated_client(self.outsider, self.athlete, scopes=["activities:read"])

        response = client.patch(f"/v1/gear/bikes/{bike.id}", {"name": "Renamed"}, format="json")
        self.assertEqual(response.status_code, 403)

        create_response = client.post("/v1/gear/bikes", {"name": "New bike"}, format="json")
        self.assertEqual(create_response.status_code, 403)

    def test_viewer_can_read_bike(self):
        UserRelationship.objects.create(
            owner=self.athlete, grantee=self.outsider, role=UserRelationship.ROLE_VIEWER, status=UserRelationship.STATUS_ACTIVE
        )
        bike = Bike.objects.create(athlete=self.athlete, name="Tarmac")
        client = _delegated_client(self.outsider, self.athlete, scopes=["activities:read"])
        response = client.get(f"/v1/gear/bikes/{bike.id}")
        self.assertEqual(response.status_code, 200)

    def test_coach_can_write_bike(self):
        UserRelationship.objects.create(
            owner=self.athlete, grantee=self.outsider, role=UserRelationship.ROLE_COACH, status=UserRelationship.STATUS_ACTIVE
        )
        bike = Bike.objects.create(athlete=self.athlete, name="Tarmac")
        client = _delegated_client(self.outsider, self.athlete, scopes=["coach"])

        response = client.patch(f"/v1/gear/bikes/{bike.id}", {"name": "Renamed"}, format="json")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["name"], "Renamed")

    def test_coach_can_create_bike_for_athlete(self):
        UserRelationship.objects.create(
            owner=self.athlete, grantee=self.outsider, role=UserRelationship.ROLE_COACH, status=UserRelationship.STATUS_ACTIVE
        )
        client = _delegated_client(self.outsider, self.athlete, scopes=["coach"])
        response = client.post("/v1/gear/bikes", {"name": "Coach added bike"}, format="json")
        self.assertEqual(response.status_code, 201)
        self.assertEqual(response.json()["athlete_id"], self.athlete.id)

    def test_outsider_without_relationship_forbidden_on_athletes_bike(self):
        bike = Bike.objects.create(athlete=self.athlete, name="Tarmac")
        client = _delegated_client(self.outsider, self.athlete, scopes=["activities:read"])
        response = client.get(f"/v1/gear/bikes/{bike.id}")
        self.assertEqual(response.status_code, 403)


@gear_urlconf
class ComponentViewTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(email="athlete@example.cc", password="x", name="Athlete")
        self.outsider = User.objects.create_user(email="outsider@example.cc", password="x", name="Outsider")
        self.bike = Bike.objects.create(athlete=self.athlete, name="Tarmac")

    def test_create_component(self):
        response = _bearer_client(self.athlete).post(
            f"/v1/gear/bikes/{self.bike.id}/components",
            {"name": "Chain", "limit_km": 4000, "km": 100, "model": "KMC X11SL"},
            format="json",
        )
        self.assertEqual(response.status_code, 201)
        data = response.json()
        self.assertEqual(data["name"], "Chain")
        self.assertEqual(data["bike_id"], self.bike.id)
        self.assertEqual(data["km"], 100)

    def test_create_component_defaults_km_to_zero(self):
        response = _bearer_client(self.athlete).post(
            f"/v1/gear/bikes/{self.bike.id}/components", {"name": "Tyres", "limit_km": 5000}, format="json"
        )
        self.assertEqual(response.status_code, 201)
        self.assertEqual(response.json()["km"], 0)

    def test_patch_component(self):
        component = Component.objects.create(bike=self.bike, name="Chain", limit_km=4000, km=100)
        response = _bearer_client(self.athlete).patch(
            f"/v1/gear/components/{component.id}", {"km": 200}, format="json"
        )
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["km"], 200)

    def test_delete_component(self):
        component = Component.objects.create(bike=self.bike, name="Chain", limit_km=4000)
        response = _bearer_client(self.athlete).delete(f"/v1/gear/components/{component.id}")
        self.assertEqual(response.status_code, 204)
        self.assertFalse(Component.objects.filter(pk=component.id).exists())

    def test_service_with_reset_zeroes_km_and_creates_record(self):
        component = Component.objects.create(bike=self.bike, name="Chain", limit_km=4000, km=3800)
        response = _bearer_client(self.athlete).post(
            f"/v1/gear/components/{component.id}/service",
            {"action": "replaced", "reset": True, "note": "New chain"},
            format="json",
        )
        self.assertEqual(response.status_code, 201)
        data = response.json()
        self.assertEqual(data["action"], "replaced")
        self.assertTrue(data["reset"])
        self.assertEqual(data["component_id"], component.id)
        component.refresh_from_db()
        self.assertEqual(component.km, 0)
        self.assertEqual(component.service_records.count(), 1)

    def test_service_with_reset_omitted_defaults_to_true(self):
        component = Component.objects.create(bike=self.bike, name="Chain", limit_km=4000, km=3800)
        response = _bearer_client(self.athlete).post(
            f"/v1/gear/components/{component.id}/service", {"action": "replaced"}, format="json"
        )
        self.assertEqual(response.status_code, 201)
        component.refresh_from_db()
        self.assertEqual(component.km, 0)

    def test_service_without_reset_leaves_km_unchanged(self):
        component = Component.objects.create(bike=self.bike, name="Chain", limit_km=4000, km=3800)
        response = _bearer_client(self.athlete).post(
            f"/v1/gear/components/{component.id}/service",
            {"action": "inspected", "reset": False},
            format="json",
        )
        self.assertEqual(response.status_code, 201)
        component.refresh_from_db()
        self.assertEqual(component.km, 3800)
        self.assertEqual(component.service_records.count(), 1)

    def test_service_date_defaults_to_today(self):
        from django.utils import timezone

        component = Component.objects.create(bike=self.bike, name="Chain", limit_km=4000)
        response = _bearer_client(self.athlete).post(
            f"/v1/gear/components/{component.id}/service", {"action": "cleaned", "reset": False}, format="json"
        )
        self.assertEqual(response.status_code, 201)
        self.assertEqual(response.json()["date"], str(timezone.localdate()))

    def test_outsider_cannot_create_component(self):
        client = _delegated_client(self.outsider, self.athlete, scopes=["activities:read"])
        response = client.post(
            f"/v1/gear/bikes/{self.bike.id}/components", {"name": "Chain", "limit_km": 4000}, format="json"
        )
        self.assertEqual(response.status_code, 403)

    def test_outsider_cannot_patch_or_service_component(self):
        component = Component.objects.create(bike=self.bike, name="Chain", limit_km=4000)
        client = _delegated_client(self.outsider, self.athlete, scopes=["activities:read"])
        self.assertEqual(client.patch(f"/v1/gear/components/{component.id}", {"km": 1}, format="json").status_code, 403)
        self.assertEqual(
            client.post(f"/v1/gear/components/{component.id}/service", {"action": "cleaned"}, format="json").status_code,
            403,
        )


@gear_urlconf
class ShoeViewTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(email="athlete@example.cc", password="x", name="Athlete")
        self.outsider = User.objects.create_user(email="outsider@example.cc", password="x", name="Outsider")
        self.shoe_model = ShoeModel.objects.create(manufacturer="Nike", model="Vaporfly")
        self.version = ShoeModelVersion.objects.create(shoe_model=self.shoe_model, version="3")

    def test_create_shoe_with_explicit_name(self):
        response = _bearer_client(self.athlete).post(
            "/v1/gear/shoes",
            {
                "shoe_model_version_id": self.version.id,
                "colourway": "Volt / Black",
                "name": "Race Day Flyers",
                "limit_km": 600,
            },
            format="json",
        )
        self.assertEqual(response.status_code, 201)
        data = response.json()
        self.assertEqual(data["name"], "Race Day Flyers")
        self.assertEqual(data["manufacturer"], "Nike")
        self.assertEqual(data["model"], "Vaporfly")
        self.assertEqual(data["version"], "3")
        self.assertEqual(data["colourway"], "Volt / Black")
        self.assertEqual(data["athlete_id"], self.athlete.id)
        self.assertIn("since", data)
        self.assertNotIn("retired", data)

    def test_create_shoe_with_omitted_name_defaults_to_composed_string(self):
        response = _bearer_client(self.athlete).post(
            "/v1/gear/shoes",
            {"shoe_model_version_id": self.version.id, "colourway": "Volt / Black"},
            format="json",
        )
        self.assertEqual(response.status_code, 201)
        self.assertEqual(response.json()["name"], "Nike Vaporfly 3 Volt / Black")

    def test_create_shoe_unknown_catalog_version_404(self):
        response = _bearer_client(self.athlete).post(
            "/v1/gear/shoes",
            {"shoe_model_version_id": "smv_doesnotexist", "colourway": "Black"},
            format="json",
        )
        self.assertEqual(response.status_code, 404)

    def test_duplicate_name_conflicts(self):
        Shoe.objects.create(
            athlete=self.athlete, shoe_model_version=self.version, colourway="Black", name="Race Day Flyers"
        )
        response = _bearer_client(self.athlete).post(
            "/v1/gear/shoes",
            {
                "shoe_model_version_id": self.version.id,
                "colourway": "White",
                "name": "Race Day Flyers",
            },
            format="json",
        )
        self.assertEqual(response.status_code, 409)

    def test_duplicate_composed_name_conflicts(self):
        Shoe.objects.create(
            athlete=self.athlete,
            shoe_model_version=self.version,
            colourway="Volt / Black",
            name="Nike Vaporfly 3 Volt / Black",
        )
        response = _bearer_client(self.athlete).post(
            "/v1/gear/shoes",
            {"shoe_model_version_id": self.version.id, "colourway": "Volt / Black"},
            format="json",
        )
        self.assertEqual(response.status_code, 409)

    def test_patch_shoe_retire_hides_from_list_but_keeps_in_db(self):
        shoe = Shoe.objects.create(
            athlete=self.athlete, shoe_model_version=self.version, colourway="Black", name="Trainers"
        )
        response = _bearer_client(self.athlete).patch(
            f"/v1/gear/shoes/{shoe.id}", {"retired": True}, format="json"
        )
        self.assertEqual(response.status_code, 200)
        self.assertNotIn("retired", response.json())

        list_response = _bearer_client(self.athlete).get("/v1/gear/shoes")
        self.assertEqual(list_response.json()["data"], [])

        shoe.refresh_from_db()
        self.assertTrue(shoe.retired)
        self.assertTrue(Shoe.objects.filter(pk=shoe.id).exists())

    def test_patch_shoe_other_fields(self):
        shoe = Shoe.objects.create(
            athlete=self.athlete, shoe_model_version=self.version, colourway="Black", name="Trainers", km=10
        )
        response = _bearer_client(self.athlete).patch(
            f"/v1/gear/shoes/{shoe.id}", {"km": 50, "limit_km": 700}, format="json"
        )
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertEqual(data["km"], 50)
        self.assertEqual(data["limit_km"], 700)

    def test_delete_shoe(self):
        shoe = Shoe.objects.create(
            athlete=self.athlete, shoe_model_version=self.version, colourway="Black", name="Trainers"
        )
        response = _bearer_client(self.athlete).delete(f"/v1/gear/shoes/{shoe.id}")
        self.assertEqual(response.status_code, 204)
        self.assertFalse(Shoe.objects.filter(pk=shoe.id).exists())

    def test_list_excludes_retired_and_scoped_to_athlete(self):
        other_athlete = User.objects.create_user(email="other@example.cc", password="x", name="Other")
        Shoe.objects.create(
            athlete=self.athlete, shoe_model_version=self.version, colourway="Black", name="Active Shoe"
        )
        Shoe.objects.create(
            athlete=self.athlete,
            shoe_model_version=self.version,
            colourway="White",
            name="Retired Shoe",
            retired=True,
        )
        Shoe.objects.create(
            athlete=other_athlete, shoe_model_version=self.version, colourway="Red", name="Other's Shoe"
        )

        response = _bearer_client(self.athlete).get("/v1/gear/shoes")
        names = [s["name"] for s in response.json()["data"]]
        self.assertEqual(names, ["Active Shoe"])

    def test_outsider_without_relationship_forbidden_on_list_and_create(self):
        client = _delegated_client(self.outsider, self.athlete, scopes=["activities:read"])
        self.assertEqual(client.get("/v1/gear/shoes").status_code, 403)
        response = client.post(
            "/v1/gear/shoes",
            {"shoe_model_version_id": self.version.id, "colourway": "Black"},
            format="json",
        )
        self.assertEqual(response.status_code, 403)

    def test_viewer_cannot_write_shoe(self):
        UserRelationship.objects.create(
            owner=self.athlete, grantee=self.outsider, role=UserRelationship.ROLE_VIEWER, status=UserRelationship.STATUS_ACTIVE
        )
        shoe = Shoe.objects.create(
            athlete=self.athlete, shoe_model_version=self.version, colourway="Black", name="Trainers"
        )
        client = _delegated_client(self.outsider, self.athlete, scopes=["activities:read"])
        response = client.patch(f"/v1/gear/shoes/{shoe.id}", {"km": 1}, format="json")
        self.assertEqual(response.status_code, 403)

    def test_coach_can_write_shoe(self):
        UserRelationship.objects.create(
            owner=self.athlete, grantee=self.outsider, role=UserRelationship.ROLE_COACH, status=UserRelationship.STATUS_ACTIVE
        )
        shoe = Shoe.objects.create(
            athlete=self.athlete, shoe_model_version=self.version, colourway="Black", name="Trainers"
        )
        client = _delegated_client(self.outsider, self.athlete, scopes=["coach"])
        response = client.patch(f"/v1/gear/shoes/{shoe.id}", {"km": 5}, format="json")
        self.assertEqual(response.status_code, 200)


@gear_urlconf
class ShoeCatalogViewTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(email="athlete@example.cc", password="x", name="Athlete")
        nike = ShoeModel.objects.create(manufacturer="Nike", model="Vaporfly")
        adidas = ShoeModel.objects.create(manufacturer="Adidas", model="Adios Pro")
        ShoeModelVersion.objects.create(shoe_model=nike, version="3")
        ShoeModelVersion.objects.create(shoe_model=adidas, version="4")

    def test_empty_query_returns_everything(self):
        response = _bearer_client(self.athlete).get("/v1/gear/shoe-catalog")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(len(response.json()["data"]), 2)

    def test_matches_by_manufacturer_case_insensitive(self):
        response = _bearer_client(self.athlete).get("/v1/gear/shoe-catalog?q=nike")
        data = response.json()["data"]
        self.assertEqual(len(data), 1)
        self.assertEqual(data[0]["manufacturer"], "Nike")
        self.assertEqual(data[0]["display_name"], "Nike Vaporfly 3")

    def test_matches_by_model_case_insensitive(self):
        response = _bearer_client(self.athlete).get("/v1/gear/shoe-catalog?q=ADIOS")
        data = response.json()["data"]
        self.assertEqual(len(data), 1)
        self.assertEqual(data[0]["manufacturer"], "Adidas")

    def test_no_match_returns_empty(self):
        response = _bearer_client(self.athlete).get("/v1/gear/shoe-catalog?q=brooks")
        self.assertEqual(response.json()["data"], [])

    def test_unauthenticated_request_rejected(self):
        client = APIClient()
        response = client.get("/v1/gear/shoe-catalog")
        self.assertEqual(response.status_code, 401)
