from datetime import timedelta

from django.test import TestCase

from accounts.models import User

from ..models import Record
from .helpers import _bearer_client, _delegated_client, _make_activity


class RecomputeActivityStatsViewTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(email="athlete@example.cc", password="x", name="Athlete", lthr=160)
        self.outsider = User.objects.create_user(email="outsider@example.cc", password="x", name="Outsider")

    def _make_activity_with_records(self):
        activity = _make_activity(self.athlete, moving_time=3600)
        for t in range(3600):
            Record.objects.create(
                activity=activity,
                t=t,
                ts=activity.start_date + timedelta(seconds=t),
                power=200,
                cadence=85,
                speed=8.0,
                altitude=100 + (t % 10),
                heartrate=160,
            )
        return activity

    def test_backfills_stats_from_stored_records(self):
        activity = self._make_activity_with_records()
        self.assertIsNone(activity.max_power)

        response = _bearer_client(self.athlete).post(f"/v1/activities/{activity.id}/recompute-stats")
        self.assertEqual(response.status_code, 200)
        body = response.json()

        self.assertEqual(body["max_power"], 200)
        self.assertEqual(body["avg_cadence"], 85)
        self.assertEqual(body["max_cadence"], 85)
        self.assertAlmostEqual(body["max_speed"], 28.8, places=1)  # 8.0 m/s -> km/h
        self.assertEqual(body["elevation_min"], 100)
        self.assertEqual(body["elevation_max"], 109)
        self.assertIsNotNone(body["calories"])
        # All samples at 100% of LTHR (160) -> Z4 Threshold -> 60 min * zone 4 = 240.
        self.assertEqual(body["trimp"], 240.0)

        activity.refresh_from_db()
        self.assertEqual(activity.max_power, 200)

    def test_missing_source_data_leaves_fields_null(self):
        activity = _make_activity(self.athlete, moving_time=60)
        Record.objects.create(activity=activity, t=0, ts=activity.start_date)

        response = _bearer_client(self.athlete).post(f"/v1/activities/{activity.id}/recompute-stats")
        self.assertEqual(response.status_code, 200)
        body = response.json()
        self.assertIsNone(body["max_power"])
        self.assertIsNone(body["avg_cadence"])
        self.assertIsNone(body["calories"])

    def test_outsider_cannot_recompute(self):
        activity = self._make_activity_with_records()
        client = _delegated_client(self.outsider, self.athlete, scopes=["activities:read"])
        response = client.post(f"/v1/activities/{activity.id}/recompute-stats")
        self.assertEqual(response.status_code, 403)
