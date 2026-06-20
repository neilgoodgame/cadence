from django.test import TestCase

from accounts.models import User

from ..models import DurationCurve
from .helpers import _bearer_client, _delegated_client, _make_activity


class CurvesViewTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(email="athlete@example.cc", password="x", name="Athlete")
        self.outsider = User.objects.create_user(email="outsider@example.cc", password="x", name="Outsider")
        self.activity = _make_activity(self.athlete)
        DurationCurve.objects.create(
            activity=self.activity, metric="power", extends_to=3600, points={"5": 400, "60": 300}
        )

    def test_get_power_curve(self):
        response = _bearer_client(self.athlete).get(f"/v1/activities/{self.activity.id}/curves")
        self.assertEqual(response.status_code, 200)
        body = response.json()
        self.assertEqual(body["metric"], "power")
        self.assertEqual(body["extends_to"], 3600)
        self.assertEqual(body["points"], {"5": 400, "60": 300})

    def test_missing_metric_curve_returns_empty(self):
        response = _bearer_client(self.athlete).get(f"/v1/activities/{self.activity.id}/curves?metric=heartrate")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json(), {"metric": "heartrate", "extends_to": 0, "points": {}})

    def test_unknown_metric_400(self):
        response = _bearer_client(self.athlete).get(f"/v1/activities/{self.activity.id}/curves?metric=bogus")
        self.assertEqual(response.status_code, 400)

    def test_outsider_forbidden(self):
        client = _delegated_client(self.outsider, self.athlete, scopes=["activities:read"])
        response = client.get(f"/v1/activities/{self.activity.id}/curves")
        self.assertEqual(response.status_code, 403)
