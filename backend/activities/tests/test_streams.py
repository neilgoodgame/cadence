from datetime import timedelta

from django.test import TestCase

from accounts.models import User

from ..models import Record
from .helpers import _bearer_client, _delegated_client, _make_activity


class StreamsViewTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(email="athlete@example.cc", password="x", name="Athlete")
        self.outsider = User.objects.create_user(email="outsider@example.cc", password="x", name="Outsider")
        self.activity = _make_activity(self.athlete, has_gps=True)
        for i in range(5):
            Record.objects.create(
                activity=self.activity,
                t=i,
                ts=self.activity.start_date + timedelta(seconds=i),
                power=200 + i,
                heartrate=140 + i,
                lat=37.0 + i * 0.001,
                lng=-122.0 - i * 0.001,
            )

    def test_default_fields_include_latlng_when_gps(self):
        response = _bearer_client(self.athlete).get(f"/v1/activities/{self.activity.id}/streams")
        self.assertEqual(response.status_code, 200)
        body = response.json()
        self.assertEqual(body["object"], "streams")
        self.assertEqual(body["resolution"], "high")
        self.assertEqual(body["fields"]["time"], [0, 1, 2, 3, 4])
        self.assertEqual(body["fields"]["power"], [200, 201, 202, 203, 204])
        self.assertEqual(body["fields"]["latlng"][0], [37.0, -122.0])

    def test_indoor_activity_omits_latlng_by_default(self):
        indoor = _make_activity(self.athlete, has_gps=False, name="Indoor")
        Record.objects.create(activity=indoor, t=0, ts=indoor.start_date, power=200)

        response = _bearer_client(self.athlete).get(f"/v1/activities/{indoor.id}/streams")
        self.assertNotIn("latlng", response.json()["fields"])

    def test_fields_param_limits_channels(self):
        response = _bearer_client(self.athlete).get(f"/v1/activities/{self.activity.id}/streams?fields=power")
        self.assertEqual(set(response.json()["fields"]), {"power"})

    def test_unknown_field_400(self):
        response = _bearer_client(self.athlete).get(f"/v1/activities/{self.activity.id}/streams?fields=bogus")
        self.assertEqual(response.status_code, 400)

    def test_unknown_resolution_400(self):
        response = _bearer_client(self.athlete).get(f"/v1/activities/{self.activity.id}/streams?resolution=ultra")
        self.assertEqual(response.status_code, 400)

    def test_low_resolution_decimates(self):
        response = _bearer_client(self.athlete).get(
            f"/v1/activities/{self.activity.id}/streams?resolution=medium&fields=time"
        )
        self.assertEqual(response.json()["fields"]["time"], [0])

    def test_decimation_keeps_every_nth_sample_relative_to_the_first(self):
        # Regression test: decimation moved from Python-slicing a fully-materialized record list
        # (records[::step]) to a DB-side MOD filter, to stop a long activity's full 1Hz record
        # set from being loaded into memory just to throw most of it away. t starts at 100, not
        # 0, to prove the kept samples are anchored to the activity's own first sample rather
        # than to t=0 - a plain "t % step == 0" would keep the wrong points here.
        activity = _make_activity(self.athlete, has_gps=False, name="Long ride")
        for i in range(20):
            Record.objects.create(
                activity=activity, t=100 + i, ts=activity.start_date + timedelta(seconds=100 + i), power=i
            )

        response = _bearer_client(self.athlete).get(
            f"/v1/activities/{activity.id}/streams?resolution=medium&fields=time"
        )
        self.assertEqual(response.json()["fields"]["time"], [100, 105, 110, 115])

    def test_outsider_forbidden(self):
        client = _delegated_client(self.outsider, self.athlete, scopes=["activities:read"])
        response = client.get(f"/v1/activities/{self.activity.id}/streams")
        self.assertEqual(response.status_code, 403)

    def test_environmental_channels_present_when_device_data_exists(self):
        Record.objects.filter(activity=self.activity).update(
            air_temp=20.0, humidity=55, core_temp=37.5, skin_temp=33.0, heat_strain=0.1
        )
        response = _bearer_client(self.athlete).get(f"/v1/activities/{self.activity.id}/streams")
        body = response.json()["fields"]
        self.assertEqual(body["air_temp"], [20.0] * 5)
        self.assertEqual(body["humidity"], [55] * 5)
        self.assertEqual(body["core_temp"], [37.5] * 5)
        self.assertEqual(body["skin_temp"], [33.0] * 5)
        self.assertEqual(body["heat_strain"], [0.1] * 5)

    def test_fields_param_includes_environmental_channels(self):
        response = _bearer_client(self.athlete).get(
            f"/v1/activities/{self.activity.id}/streams?fields=air_temp,humidity"
        )
        self.assertEqual(set(response.json()["fields"]), {"air_temp", "humidity"})
