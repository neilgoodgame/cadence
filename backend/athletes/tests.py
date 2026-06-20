from datetime import UTC, date, datetime, timedelta

from django.test import TestCase
from django.utils import timezone
from rest_framework.test import APIClient

from accounts.models import User, UserRelationship
from activities.models import Activity, BestEffort
from authn.jwt_utils import mint_jwt
from authn.oauth_utils import issue_token_pair

from .models import ZoneSet
from .zones import DEFAULT_ZONES


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


class AthleteDetailViewTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(
            email="athlete@example.cc", password="x", name="Athlete", ftp=250, lthr=160, threshold_pace="4:00"
        )
        self.outsider = User.objects.create_user(email="outsider@example.cc", password="x", name="Outsider")

    def test_self_can_read_own_profile(self):
        response = _bearer_client(self.athlete).get(f"/v1/athletes/{self.athlete.id}")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["ftp"], 250)

    def test_outsider_without_relationship_is_forbidden(self):
        response = _bearer_client(self.outsider).get(f"/v1/athletes/{self.athlete.id}")
        self.assertEqual(response.status_code, 403)

    def test_active_viewer_can_read_via_delegated_jwt(self):
        UserRelationship.objects.create(
            owner=self.athlete,
            grantee=self.outsider,
            role=UserRelationship.ROLE_VIEWER,
            status=UserRelationship.STATUS_ACTIVE,
        )
        client = _delegated_client(self.outsider, self.athlete, scopes=["activities:read"])
        response = client.get(f"/v1/athletes/{self.athlete.id}")
        self.assertEqual(response.status_code, 200)

    def test_self_can_update_thresholds(self):
        response = _bearer_client(self.athlete).patch(f"/v1/athletes/{self.athlete.id}", {"ftp": 280}, format="json")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["ftp"], 280)
        self.athlete.refresh_from_db()
        self.assertEqual(self.athlete.ftp, 280)

    def test_update_with_no_existing_zone_set_reports_no_recompute(self):
        response = _bearer_client(self.athlete).patch(f"/v1/athletes/{self.athlete.id}", {"ftp": 280}, format="json")
        self.assertEqual(response.json()["zones_recomputed"], [])

    def test_update_with_existing_zone_set_reports_recompute(self):
        ZoneSet.objects.create(athlete=self.athlete, type="bike_power", zones=DEFAULT_ZONES)
        response = _bearer_client(self.athlete).patch(f"/v1/athletes/{self.athlete.id}", {"ftp": 280}, format="json")
        self.assertEqual(response.json()["zones_recomputed"], ["bike_power"])

    def test_viewer_cannot_write(self):
        UserRelationship.objects.create(
            owner=self.athlete,
            grantee=self.outsider,
            role=UserRelationship.ROLE_VIEWER,
            status=UserRelationship.STATUS_ACTIVE,
        )
        client = _delegated_client(self.outsider, self.athlete, scopes=["activities:read"])
        response = client.patch(f"/v1/athletes/{self.athlete.id}", {"ftp": 999}, format="json")
        self.assertEqual(response.status_code, 403)

    def test_coach_can_write(self):
        UserRelationship.objects.create(
            owner=self.athlete,
            grantee=self.outsider,
            role=UserRelationship.ROLE_COACH,
            status=UserRelationship.STATUS_ACTIVE,
        )
        client = _delegated_client(self.outsider, self.athlete, scopes=["calendar:write"])
        response = client.patch(f"/v1/athletes/{self.athlete.id}", {"ftp": 300}, format="json")
        self.assertEqual(response.status_code, 200)


class ZoneSetViewTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(
            email="athlete@example.cc",
            password="x",
            name="Athlete",
            ftp=250,
            lthr=160,
            critical_run_power=270,
            threshold_pace="4:00",
        )
        self.outsider = User.objects.create_user(email="outsider@example.cc", password="x", name="Outsider")

    def test_list_lazily_seeds_default_zones_for_all_four_types(self):
        response = _bearer_client(self.athlete).get(f"/v1/athletes/{self.athlete.id}/zones")
        self.assertEqual(response.status_code, 200)
        data = response.json()["data"]
        self.assertEqual({z["type"] for z in data}, {"heart_rate", "bike_power", "run_power", "pace"})
        bike = next(z for z in data if z["type"] == "bike_power")
        self.assertEqual(bike["reference"], 250)
        self.assertEqual(bike["zones"], DEFAULT_ZONES)

    def test_pace_reference_is_seconds_from_mmss(self):
        response = _bearer_client(self.athlete).get(f"/v1/athletes/{self.athlete.id}/zones")
        pace = next(z for z in response.json()["data"] if z["type"] == "pace")
        self.assertEqual(pace["reference"], 240)

    def test_outsider_without_relationship_cannot_list(self):
        response = _bearer_client(self.outsider).get(f"/v1/athletes/{self.athlete.id}/zones")
        self.assertEqual(response.status_code, 403)

    def test_replace_zone_set(self):
        new_zones = [{"name": "Z1", "low_pct": 0, "high_pct": 60}, {"name": "Z2", "low_pct": 61, "high_pct": 100}]
        response = _bearer_client(self.athlete).put(
            f"/v1/athletes/{self.athlete.id}/zones/bike_power", {"zones": new_zones}, format="json"
        )
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json(), {"type": "bike_power", "reference": 250, "updated": True})
        zone_set = ZoneSet.objects.get(athlete=self.athlete, type="bike_power")
        self.assertEqual(zone_set.zones, new_zones)

    def test_replace_unknown_type_is_rejected(self):
        response = _bearer_client(self.athlete).put(
            f"/v1/athletes/{self.athlete.id}/zones/bananas", {"zones": []}, format="json"
        )
        self.assertEqual(response.status_code, 400)

    def test_replace_missing_zones_field_is_rejected(self):
        response = _bearer_client(self.athlete).put(
            f"/v1/athletes/{self.athlete.id}/zones/bike_power", {}, format="json"
        )
        self.assertEqual(response.status_code, 400)

    def test_viewer_cannot_replace(self):
        UserRelationship.objects.create(
            owner=self.athlete,
            grantee=self.outsider,
            role=UserRelationship.ROLE_VIEWER,
            status=UserRelationship.STATUS_ACTIVE,
        )
        client = _delegated_client(self.outsider, self.athlete, scopes=["activities:read"])
        response = client.put(f"/v1/athletes/{self.athlete.id}/zones/bike_power", {"zones": []}, format="json")
        self.assertEqual(response.status_code, 403)


class BestEffortListViewTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(email="athlete@example.cc", password="x", name="Athlete")
        self.outsider = User.objects.create_user(email="outsider@example.cc", password="x", name="Outsider")

        recent_activity = Activity.objects.create(
            athlete=self.athlete, sport="bike", name="Ride", start_date=timezone.now(), moving_time=300, distance_km=10
        )
        BestEffort.objects.create(
            athlete=self.athlete,
            kind="cycling_power",
            window="5min",
            value=300,
            unit="watts",
            date=date.today(),
            activity=recent_activity,
        )

        old_activity = Activity.objects.create(
            athlete=self.athlete,
            sport="bike",
            name="Old Ride",
            start_date=timezone.now() - timedelta(days=400),
            moving_time=300,
            distance_km=10,
        )
        BestEffort.objects.create(
            athlete=self.athlete,
            kind="cycling_power",
            window="20min",
            value=250,
            unit="watts",
            date=date.today() - timedelta(days=400),
            activity=old_activity,
        )

    def test_list_requires_kind(self):
        response = _bearer_client(self.athlete).get(f"/v1/athletes/{self.athlete.id}/best-efforts")
        self.assertEqual(response.status_code, 400)

    def test_list_all_period_returns_everything(self):
        response = _bearer_client(self.athlete).get(f"/v1/athletes/{self.athlete.id}/best-efforts?kind=cycling_power")
        body = response.json()
        self.assertEqual(body["kind"], "cycling_power")
        self.assertEqual(body["period"], "all")
        windows = {e["window"] for e in body["data"]}
        self.assertEqual(windows, {"5min", "20min"})

    def test_period_filters_by_date(self):
        response = _bearer_client(self.athlete).get(
            f"/v1/athletes/{self.athlete.id}/best-efforts?kind=cycling_power&period=1y"
        )
        windows = {e["window"] for e in response.json()["data"]}
        self.assertEqual(windows, {"5min"})

    def test_unknown_kind_400(self):
        response = _bearer_client(self.athlete).get(f"/v1/athletes/{self.athlete.id}/best-efforts?kind=bogus")
        self.assertEqual(response.status_code, 400)

    def test_outsider_forbidden(self):
        response = _bearer_client(self.outsider).get(f"/v1/athletes/{self.athlete.id}/best-efforts?kind=cycling_power")
        self.assertEqual(response.status_code, 403)


class FitnessListViewTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(email="athlete@example.cc", password="x", name="Athlete")
        self.outsider = User.objects.create_user(email="outsider@example.cc", password="x", name="Outsider")
        self.day1 = date(2026, 1, 1)
        self.day2 = date(2026, 1, 2)
        Activity.objects.create(
            athlete=self.athlete,
            sport="bike",
            name="Day 1",
            start_date=datetime(2026, 1, 1, 10, 0, tzinfo=UTC),
            moving_time=3600,
            distance_km=30,
            tss=100,
        )
        Activity.objects.create(
            athlete=self.athlete,
            sport="bike",
            name="Day 2",
            start_date=datetime(2026, 1, 2, 10, 0, tzinfo=UTC),
            moving_time=1800,
            distance_km=15,
            tss=50,
        )

    def test_no_activities_returns_zero_series(self):
        empty_athlete = User.objects.create_user(email="empty@example.cc", password="x", name="Empty")
        response = _bearer_client(empty_athlete).get(
            f"/v1/athletes/{empty_athlete.id}/fitness?from=2026-01-01&to=2026-01-01"
        )
        self.assertEqual(response.status_code, 200)
        data = response.json()["data"]
        self.assertEqual(data, [{"date": "2026-01-01", "ctl": 0.0, "atl": 0.0, "tsb": 0.0}])

    def test_computes_ctl_atl_tsb_from_daily_tss(self):
        response = _bearer_client(self.athlete).get(
            f"/v1/athletes/{self.athlete.id}/fitness?from=2026-01-01&to=2026-01-02"
        )
        self.assertEqual(response.status_code, 200)
        data = response.json()["data"]
        self.assertEqual(len(data), 2)
        self.assertEqual(data[0], {"date": "2026-01-01", "ctl": 2.4, "atl": 14.3, "tsb": -11.9})
        self.assertEqual(data[1], {"date": "2026-01-02", "ctl": 3.5, "atl": 19.4, "tsb": -15.9})

    def test_narrows_to_from_to_window(self):
        response = _bearer_client(self.athlete).get(
            f"/v1/athletes/{self.athlete.id}/fitness?from=2026-01-02&to=2026-01-02"
        )
        data = response.json()["data"]
        self.assertEqual(len(data), 1)
        self.assertEqual(data[0]["date"], "2026-01-02")

    def test_invalid_date_returns_400(self):
        response = _bearer_client(self.athlete).get(f"/v1/athletes/{self.athlete.id}/fitness?from=not-a-date")
        self.assertEqual(response.status_code, 400)

    def test_from_after_to_returns_400(self):
        response = _bearer_client(self.athlete).get(
            f"/v1/athletes/{self.athlete.id}/fitness?from=2026-01-02&to=2026-01-01"
        )
        self.assertEqual(response.status_code, 400)

    def test_outsider_forbidden(self):
        response = _bearer_client(self.outsider).get(f"/v1/athletes/{self.athlete.id}/fitness")
        self.assertEqual(response.status_code, 403)
