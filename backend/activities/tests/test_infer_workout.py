from django.test import TestCase

from accounts.models import User

from ..models import Lap
from .helpers import _bearer_client, _delegated_client, _make_activity


class InferWorkoutViewTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(email="athlete@example.cc", password="x", name="Athlete", ftp=250)
        self.outsider = User.objects.create_user(email="outsider@example.cc", password="x", name="Outsider")

    def test_infers_steps_from_laps(self):
        activity = _make_activity(self.athlete, sport="bike")
        Lap.objects.create(activity=activity, index=0, duration=600, distance_km=1.0, avg_power=140)
        Lap.objects.create(activity=activity, index=1, duration=300, distance_km=1.0, avg_power=300)

        response = _bearer_client(self.athlete).get(f"/v1/activities/{activity.id}/infer-workout")
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertEqual(data["sport"], "bike")
        self.assertEqual(len(data["steps"]), 2)

    def test_auto_detect_repeats_query_param_false_stays_flat(self):
        activity = _make_activity(self.athlete, sport="bike")
        for i in range(6):
            Lap.objects.create(activity=activity, index=i, duration=300, distance_km=1.0, avg_power=300)

        response = _bearer_client(self.athlete).get(
            f"/v1/activities/{activity.id}/infer-workout", {"auto_detect_repeats": "false"}
        )
        self.assertEqual(response.status_code, 200)
        steps = response.json()["steps"]
        self.assertEqual(len(steps), 6)

    def test_rejects_unsupported_sport(self):
        activity = _make_activity(self.athlete, sport="swim")
        Lap.objects.create(activity=activity, index=0, duration=300, distance_km=1.0)

        response = _bearer_client(self.athlete).get(f"/v1/activities/{activity.id}/infer-workout")
        self.assertEqual(response.status_code, 400)

    def test_rejects_activity_with_no_laps(self):
        activity = _make_activity(self.athlete, sport="bike")

        response = _bearer_client(self.athlete).get(f"/v1/activities/{activity.id}/infer-workout")
        self.assertEqual(response.status_code, 400)

    def test_outsider_forbidden(self):
        activity = _make_activity(self.athlete, sport="bike")
        Lap.objects.create(activity=activity, index=0, duration=300, distance_km=1.0)
        client = _delegated_client(self.outsider, self.athlete, scopes=["activities:read"])
        response = client.get(f"/v1/activities/{activity.id}/infer-workout")
        self.assertEqual(response.status_code, 403)
