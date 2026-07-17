from datetime import date

from django.test import TestCase

from accounts.models import User
from core.derived import _daily_tss

from ..models import Activity
from .helpers import _bearer_client, _make_activity


class DuplicateLinkTests(TestCase):
    """Linking two recordings of the same session: the duplicate points at the
    primary via primary_activity_id and only the primary counts toward lists,
    the calendar, and training load."""

    def setUp(self):
        self.athlete = User.objects.create_user(email="athlete@example.cc", password="x", name="Athlete")
        self.primary = _make_activity(self.athlete, sport="bike", name="Zwift ride", tss=80)
        self.duplicate = _make_activity(self.athlete, sport="bike", name="Head unit ride", tss=85)

    def _link(self, activity, primary_id):
        return _bearer_client(self.athlete).patch(
            f"/v1/activities/{activity.id}", {"primary_activity_id": primary_id}, format="json"
        )

    def test_link_and_unlink(self):
        response = self._link(self.duplicate, self.primary.id)
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["primary_activity_id"], self.primary.id)

        detail = _bearer_client(self.athlete).get(f"/v1/activities/{self.primary.id}")
        self.assertEqual(detail.json()["duplicate_activity_ids"], [self.duplicate.id])

        response = self._link(self.duplicate, None)
        self.assertEqual(response.status_code, 200)
        self.assertIsNone(response.json()["primary_activity_id"])

    def test_self_link_rejected(self):
        response = self._link(self.duplicate, self.duplicate.id)
        self.assertEqual(response.status_code, 400)

    def test_chain_rejected(self):
        self._link(self.duplicate, self.primary.id)
        third = _make_activity(self.athlete, sport="bike", name="Third recording")
        response = self._link(third, self.duplicate.id)
        self.assertEqual(response.status_code, 400)

    def test_primary_with_duplicates_cannot_become_duplicate(self):
        self._link(self.duplicate, self.primary.id)
        other = _make_activity(self.athlete, sport="bike", name="Other ride")
        response = self._link(self.primary, other.id)
        self.assertEqual(response.status_code, 400)

    def test_other_athletes_activity_404(self):
        other_athlete = User.objects.create_user(email="other@example.cc", password="x", name="Other")
        theirs = _make_activity(other_athlete, sport="bike", name="Not mine")
        response = self._link(self.duplicate, theirs.id)
        self.assertEqual(response.status_code, 404)

    def test_multisport_parent_and_leg_rejected(self):
        parent = _make_activity(self.athlete, sport="multisport", name="Race")
        leg = _make_activity(self.athlete, sport="run", name="Run leg", parent_activity=parent)
        self.assertEqual(self._link(self.duplicate, parent.id).status_code, 400)
        self.assertEqual(self._link(self.duplicate, leg.id).status_code, 400)
        self.assertEqual(self._link(leg, self.primary.id).status_code, 400)

    def test_duplicate_hidden_from_list(self):
        self._link(self.duplicate, self.primary.id)
        response = _bearer_client(self.athlete).get("/v1/activities")
        ids = [a["id"] for a in response.json()["data"]]
        self.assertIn(self.primary.id, ids)
        self.assertNotIn(self.duplicate.id, ids)

    def test_duplicate_hidden_from_calendar_unplanned(self):
        self._link(self.duplicate, self.primary.id)
        response = _bearer_client(self.athlete).get("/v1/calendar?from=2026-01-01&to=2026-01-02")
        ids = [a["id"] for a in response.json()["unplanned_activities"]]
        self.assertIn(self.primary.id, ids)
        self.assertNotIn(self.duplicate.id, ids)

    def test_duplicate_excluded_from_training_load(self):
        day = date(2026, 1, 1)
        self.assertEqual(_daily_tss(self.athlete.id, day, day)[day], 165)
        self._link(self.duplicate, self.primary.id)
        self.assertEqual(_daily_tss(self.athlete.id, day, day)[day], 80)

    def test_deleting_primary_frees_duplicate(self):
        self._link(self.duplicate, self.primary.id)
        _bearer_client(self.athlete).delete(f"/v1/activities/{self.primary.id}")
        self.duplicate.refresh_from_db()
        self.assertIsNone(self.duplicate.primary_activity_id)
        self.assertTrue(Activity.objects.filter(pk=self.duplicate.id).exists())

    def test_duplicate_ids_empty_on_duplicate_itself(self):
        self._link(self.duplicate, self.primary.id)
        detail = _bearer_client(self.athlete).get(f"/v1/activities/{self.duplicate.id}")
        self.assertEqual(detail.json()["duplicate_activity_ids"], [])
