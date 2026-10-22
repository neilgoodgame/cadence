from django.test import TestCase, override_settings
from rest_framework.test import APIClient

from accounts.models import User
from authn.oauth_utils import issue_token_pair
from workouts.models import Workout

from . import crypto
from .models import GarminConnection

KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
TCX = "<TrainingCenterDatabase/>"


def _client(user):
    access_token, _ = issue_token_pair(user, scope="workouts:write")
    client = APIClient()
    client.credentials(HTTP_AUTHORIZATION=f"Bearer {access_token.token}")
    return client


@override_settings(GARMIN_ENCRYPTION_KEY=KEY)
class GarminWorkoutPushTests(TestCase):
    """The workout-push outbox: what the browser queues and what the local bridge reports back."""

    def _athlete(self, email, connected=True):
        user = User.objects.create_user(email=email, password="x", name="Athlete")
        if connected:
            GarminConnection.objects.create(
                owner=user, garmin_email=email, api_key_encrypted=crypto.encrypt("garmin-wpush-key-0001", user.id)
            )
        return user, _client(user)

    def _workout(self, owner, name="w"):
        return Workout.objects.create(created_by=owner, name=name, sport="bike")

    def _upsert(self, client, workout_id, tcx=TCX):
        return client.post(
            "/v1/garmin/workout-ops",
            {"kind": "upsert_workout", "workout_id": workout_id, "name": "Test", "tcx": tcx},
            format="json",
        )

    def _schedule(self, client, kind, workout_id, scheduled_id, date=None):
        body = {"kind": kind, "workout_id": workout_id, "scheduled_workout_id": scheduled_id}
        if date:
            body["date"] = date
        return client.post("/v1/garmin/workout-ops", body, format="json")

    def _report(self, client, op_id, body):
        return client.post(f"/v1/garmin/workout-ops/{op_id}/result", body, format="json")

    def test_queuing_needs_a_garmin_connection(self):
        user, client = self._athlete("garmin-wpush-noconn@example.cc", connected=False)
        self.assertEqual(self._upsert(client, self._workout(user).id).status_code, 409)

    def test_an_unknown_or_someone_elses_workout_is_not_found(self):
        _, client = self._athlete("garmin-wpush-owner@example.cc")
        other, _ = self._athlete("garmin-wpush-other@example.cc")
        self.assertEqual(self._upsert(client, self._workout(other).id).status_code, 404)
        self.assertEqual(self._upsert(client, "wkt_nope").status_code, 404)

    def test_a_pushed_workout_is_merged_while_pending_and_linked_once_the_bridge_reports(self):
        user, client = self._athlete("garmin-wpush-upsert@example.cc")
        workout = self._workout(user)

        first = self._upsert(client, workout.id)
        self.assertEqual(first.status_code, 202)
        op_id = first.json()["id"]
        self._upsert(client, workout.id, "<Edited/>")

        pending = client.get("/v1/garmin/workout-ops").json()["data"]
        self.assertEqual(len(pending), 1)
        self.assertEqual((pending[0]["id"], pending[0]["kind"]), (op_id, "upsert_workout"))
        self.assertEqual(pending[0]["tcx"], "<Edited/>")
        self.assertIsNone(pending[0]["garmin_workout_id"])

        self.assertEqual(self._report(client, op_id, {"status": "done", "garmin_workout_id": 555}).status_code, 200)

        self.assertEqual(client.get("/v1/garmin/workout-ops").json()["data"], [])
        links = client.get("/v1/garmin/workout-links").json()
        self.assertEqual(
            (links["workouts"][0]["workout_id"], links["workouts"][0]["garmin_workout_id"]), (workout.id, 555)
        )
        self.assertEqual(links["pending"], 0)

    def test_schedule_reschedule_and_cancel_follow_the_garmin_ids(self):
        user, client = self._athlete("garmin-wpush-schedule@example.cc")
        workout = self._workout(user)
        upsert = self._upsert(client, workout.id).json()["id"]
        self._report(client, upsert, {"status": "done", "garmin_workout_id": 555})

        schedule = self._schedule(client, "schedule", workout.id, "sch_1", "2026-11-01")
        self.assertEqual(schedule.status_code, 202)
        body = schedule.json()
        self.assertEqual((body["kind"], body["garmin_workout_id"]), ("schedule", 555))
        self.assertEqual(
            self._report(client, body["id"], {"status": "done", "garmin_schedule_id": 777}).status_code, 200
        )

        # Moving it is now a reschedule that knows the Garmin schedule id; a second move merges into it.
        move = self._schedule(client, "reschedule", workout.id, "sch_1", "2026-11-02").json()
        self.assertEqual((move["kind"], move["garmin_schedule_id"]), ("reschedule", 777))
        self._schedule(client, "reschedule", workout.id, "sch_1", "2026-11-03")
        pending = client.get("/v1/garmin/workout-ops").json()["data"]
        self.assertEqual((len(pending), pending[0]["date"]), (1, "2026-11-03"))
        self._report(client, move["id"], {"status": "done", "garmin_schedule_id": 778, "garmin_workout_id": 555})
        schedules = client.get("/v1/garmin/workout-links").json()["schedules"]
        self.assertEqual((schedules[0]["garmin_schedule_id"], schedules[0]["scheduled_for"]), (778, "2026-11-03"))

        cancel = self._schedule(client, "unschedule", workout.id, "sch_1")
        self.assertEqual(cancel.status_code, 202)
        self.assertEqual(cancel.json()["garmin_schedule_id"], 778)
        self._report(client, cancel.json()["id"], {"status": "done"})
        self.assertEqual(client.get("/v1/garmin/workout-links").json()["schedules"], [])

    def test_cancelling_a_schedule_that_never_reached_garmin_just_drops_it(self):
        user, client = self._athlete("garmin-wpush-drop@example.cc")
        workout = self._workout(user)
        self._schedule(client, "schedule", workout.id, "sch_drop", "2026-11-01")

        self.assertEqual(self._schedule(client, "unschedule", workout.id, "sch_drop").status_code, 204)
        self.assertEqual(client.get("/v1/garmin/workout-ops").json()["data"], [])

    def test_a_failure_is_kept_with_its_reason_and_can_be_discarded(self):
        user, client = self._athlete("garmin-wpush-fail@example.cc")
        op_id = self._upsert(client, self._workout(user).id).json()["id"]

        self.assertEqual(self._report(client, op_id, {"status": "failed", "error": "Garmin said no"}).status_code, 200)

        failed = client.get("/v1/garmin/workout-ops?status=failed").json()["data"]
        self.assertEqual((failed[0]["error"], failed[0]["tcx"]), ("Garmin said no", None))
        self.assertEqual(client.get("/v1/garmin/workout-links").json()["failed"], 1)
        self.assertEqual(client.delete(f"/v1/garmin/workout-ops/{op_id}").status_code, 204)
        self.assertEqual(client.get("/v1/garmin/workout-ops?status=failed").json()["data"], [])

    def test_results_are_validated_and_only_the_owner_can_report_them(self):
        user, client = self._athlete("garmin-wpush-validate@example.cc")
        _, other_client = self._athlete("garmin-wpush-validate-other@example.cc")
        op_id = self._upsert(client, self._workout(user).id).json()["id"]

        self.assertEqual(self._report(other_client, op_id, {"status": "done", "garmin_workout_id": 1}).status_code, 404)
        self.assertEqual(self._report(client, op_id, {"status": "bogus"}).status_code, 400)
        self.assertEqual(self._report(client, op_id, {"status": "done"}).status_code, 400)
        self.assertEqual(self._report(client, op_id, {"status": "done", "garmin_workout_id": 9}).status_code, 200)
        self.assertEqual(self._report(client, op_id, {"status": "done", "garmin_workout_id": 9}).status_code, 409)
