from datetime import timedelta

from django.test import Client, TestCase

from accounts.models import User
from activities.models import Lap
from activities.tests.helpers import _bearer_client, _json, _make_activity
from courses.models import Course
from races.models import Race


class PublicRaceTests(TestCase):
    """is_public opt-in plus the unauthenticated /v1/public/races/<id> read endpoints. Mirrors
    the Java backend's PublicRaceIntegrationTest."""

    def setUp(self):
        self.athlete = User.objects.create_user(email="publicrace-athlete@example.cc", password="x", name="Pat Racer")
        self.outsider = User.objects.create_user(email="publicrace-outsider@example.cc", password="x", name="Outsider")
        self.activity = _make_activity(self.athlete, distance_km=2.0, avg_hr=150, avg_power=250)
        Lap.objects.create(activity=self.activity, index=1, duration=300, distance_km=1.0, avg_hr=148)
        Lap.objects.create(activity=self.activity, index=2, duration=290, distance_km=1.0, avg_hr=152)
        self.course = Course.objects.create(
            athlete=self.athlete,
            name="Parkland 2K",
            location_name="Somewhere",
            source="gpx",
            gpx_filename="secret-home-loop.gpx",
            distance_km=2.0,
            route_polyline=[[51.5, -0.1], [51.51, -0.11]],
            elevation_profile=[[0.0, 10.0], [2.0, 12.0]],
            elevation_quality="missing",
        )
        self.race = Race.objects.create(
            athlete=self.athlete,
            name="Club 2K",
            date=self.activity.start_date.date(),
            sport="run",
            distance_km=2.0,
            goal_time=timedelta(minutes=10),
            result_time=timedelta(minutes=9, seconds=50),
            target_power=260,
            notes="private notes",
            activity=self.activity,
            course=self.course,
        )
        self.anonymous = Client()

    def _publish(self, value=True):
        return _bearer_client(self.athlete).patch(f"/v1/races/{self.race.id}", {"is_public": value}, format="json")

    def test_races_are_private_by_default(self):
        body = _json(_bearer_client(self.athlete).get(f"/v1/races/{self.race.id}"))
        self.assertIs(body["is_public"], False)
        for suffix in ("", "/splits", "/weather"):
            self.assertEqual(self.anonymous.get(f"/v1/public/races/{self.race.id}{suffix}").status_code, 404)

    def test_owner_can_publish_and_anyone_can_then_read_it(self):
        response = self._publish()
        self.assertEqual(response.status_code, 200)
        self.assertIs(_json(response)["is_public"], True)

        body = _json(self.anonymous.get(f"/v1/public/races/{self.race.id}"))
        self.assertEqual(body["athlete_name"], "Pat Racer")
        self.assertEqual(body["name"], "Club 2K")
        self.assertEqual(body["goal_time"], "00:10:00")
        self.assertEqual(body["result_time"], "00:09:50")
        self.assertEqual(
            body["activity"], {"avg_hr": 150, "avg_power": 250, "avg_air_temp": None, "avg_humidity": None}
        )
        self.assertEqual(body["course"]["name"], "Parkland 2K")
        self.assertEqual(body["course"]["route_polyline"], [[51.5, -0.1], [51.51, -0.11]])
        # Allowlisted payload only - nothing from the athlete's training or the course's origin.
        for private_field in ("athlete_id", "notes", "priority", "target_power", "activity_id", "is_public"):
            self.assertNotIn(private_field, body)
        for private_field in ("athlete_id", "gpx_filename", "source_activity_id", "races"):
            self.assertNotIn(private_field, body["course"])

    def test_public_lap_splits(self):
        self._publish()
        response = self.anonymous.get(f"/v1/public/races/{self.race.id}/splits?mode=laps")
        self.assertEqual(response.status_code, 200)
        body = _json(response)
        self.assertTrue(body["has_laps"])
        self.assertEqual([s["time_s"] for s in body["splits"]], [300, 290])

    def test_public_splits_reject_unknown_mode_and_custom_splits(self):
        self._publish()
        self.assertEqual(self.anonymous.get(f"/v1/public/races/{self.race.id}/splits?mode=bogus").status_code, 400)
        response = self.anonymous.post(
            f"/v1/public/races/{self.race.id}/splits", {"steps": []}, content_type="application/json"
        )
        self.assertEqual(response.status_code, 405)

    def test_public_weather_for_a_past_race_is_unavailable_not_an_error(self):
        self._publish()
        response = self.anonymous.get(f"/v1/public/races/{self.race.id}/weather")
        self.assertEqual(response.status_code, 200)
        self.assertIs(_json(response)["available"], False)

    def test_unpublishing_revokes_public_access(self):
        self._publish(True)
        self._publish(False)
        self.assertEqual(self.anonymous.get(f"/v1/public/races/{self.race.id}").status_code, 404)

    def test_only_a_writer_can_change_is_public(self):
        response = _bearer_client(self.outsider).patch(f"/v1/races/{self.race.id}", {"is_public": True}, format="json")
        self.assertEqual(response.status_code, 403)
        self.race.refresh_from_db()
        self.assertFalse(self.race.is_public)

    def test_public_race_does_not_open_up_the_authenticated_endpoints(self):
        self._publish()
        self.assertEqual(self.anonymous.get(f"/v1/races/{self.race.id}").status_code, 401)
        self.assertEqual(self.anonymous.get(f"/v1/races/{self.race.id}/block").status_code, 401)
