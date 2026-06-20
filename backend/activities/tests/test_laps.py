from django.test import TestCase

from accounts.models import User

from ..models import Lap
from .helpers import _bearer_client, _delegated_client, _make_activity


class LapListViewTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(email="athlete@example.cc", password="x", name="Athlete")
        self.outsider = User.objects.create_user(email="outsider@example.cc", password="x", name="Outsider")

    def test_lists_laps_in_order(self):
        activity = _make_activity(self.athlete)
        Lap.objects.create(activity=activity, index=2, duration=300, distance_km=1.0)
        Lap.objects.create(activity=activity, index=1, duration=290, distance_km=1.0)

        response = _bearer_client(self.athlete).get(f"/v1/activities/{activity.id}/laps")
        self.assertEqual(response.status_code, 200)
        indexes = [lap["index"] for lap in response.json()["data"]]
        self.assertEqual(indexes, [1, 2])

    def test_outsider_forbidden(self):
        activity = _make_activity(self.athlete)
        client = _delegated_client(self.outsider, self.athlete, scopes=["activities:read"])
        response = client.get(f"/v1/activities/{activity.id}/laps")
        self.assertEqual(response.status_code, 403)
