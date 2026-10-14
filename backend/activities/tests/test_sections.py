from datetime import timedelta

from django.test import TestCase

from accounts.models import User

from ..models import ActivityElevation, Record
from .helpers import _bearer_client, _make_activity


def _altitude(km: float) -> float:
    """Flat for 1 km, a 600 m climb at 5%, then flat again."""
    if km <= 1.0:
        return 50.0
    if km <= 1.6:
        return 50.0 + (km - 1.0) * 50.0
    return 80.0


def _add_records(activity, seconds: int = 1000, altitude=_altitude) -> None:
    Record.objects.bulk_create(
        Record(
            activity=activity,
            t=i,
            ts=activity.start_date + timedelta(seconds=i),
            distance_km=i * 0.003,
            altitude=altitude(i * 0.003),
        )
        for i in range(seconds)
    )


class ActivitySectionsTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(email="actsections@example.cc", password="x", name="Athlete")
        self.outsider = User.objects.create_user(email="actsections-out@example.cc", password="x", name="Outsider")
        self.activity = _make_activity(self.athlete, sport="run", has_gps=True)
        _add_records(self.activity)

    def _get(self, query: str = "", user=None):
        return _bearer_client(user or self.athlete).get(f"/v1/activities/{self.activity.id}/sections{query}")

    def test_first_read_builds_the_series_and_finds_the_climb(self):
        self.assertFalse(ActivityElevation.objects.filter(activity=self.activity).exists())
        response = self._get()
        self.assertEqual(response.status_code, 200)
        body = response.json()
        self.assertEqual(body["object"], "activity_sections")
        self.assertEqual(body["activity_id"], self.activity.id)
        self.assertEqual(body["elevation_quality"], "ok")
        self.assertEqual([s["type"] for s in body["sections"]], ["flat", "steep_climb", "flat"])
        self.assertEqual(len(body["climbs"]), 1)
        self.assertEqual(body["elevation_profile"][0], [0.0, 50.0])
        self.assertLessEqual(len(body["elevation_profile"]), 200)
        stored = ActivityElevation.objects.get(activity=self.activity)
        self.assertEqual(stored.elevation_quality, "ok")
        self.assertEqual(len(stored.elevation_series), 300)

    def test_preview_overrides_apply_without_saving(self):
        response = self._get("?steep_min_pct=6")
        self.assertEqual([s["type"] for s in response.json()["sections"]], ["flat", "climb", "flat"])
        self.assertEqual(self.athlete.section_settings().steep_min_pct, 4.0)

    def test_invalid_override_400(self):
        self.assertEqual(self._get("?fit_tolerance_m=0.1").status_code, 400)
        self.assertEqual(self._get("?fit_tolerance_m=abc").status_code, 400)

    def test_no_altitude_is_missing(self):
        flat = _make_activity(self.athlete, sport="bike", name="No altitude")
        _add_records(flat, altitude=lambda km: None)
        body = _bearer_client(self.athlete).get(f"/v1/activities/{flat.id}/sections").json()
        self.assertEqual(body["elevation_quality"], "missing")
        self.assertEqual(body["sections"], [])
        self.assertEqual(body["elevation_profile"], [])

    def test_other_sports_422(self):
        swim = _make_activity(self.athlete, sport="swim", name="Swim")
        response = _bearer_client(self.athlete).get(f"/v1/activities/{swim.id}/sections")
        self.assertEqual(response.status_code, 422)

    def test_outsider_403(self):
        self.assertEqual(self._get(user=self.outsider).status_code, 403)

    def test_recompute_rebuilds_from_the_records(self):
        self._get()
        ActivityElevation.objects.filter(activity=self.activity).update(
            elevation_quality="missing", elevation_series=[]
        )
        self.assertEqual(self._get().json()["sections"], [])
        response = _bearer_client(self.athlete).post(f"/v1/activities/{self.activity.id}/recompute-elevation")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["elevation_quality"], "ok")
        self.assertEqual(len(response.json()["sections"]), 3)

    def test_recompute_needs_write_access(self):
        response = _bearer_client(self.outsider).post(f"/v1/activities/{self.activity.id}/recompute-elevation")
        self.assertEqual(response.status_code, 403)
