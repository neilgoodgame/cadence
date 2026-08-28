from datetime import UTC, date, datetime

from django.test import TestCase

from accounts.models import User, UserRelationship
from athletes.models import ThresholdHistory
from workouts.models import Workout

from ..models import Activity, ActivityTag, Lap, Record, Tag
from .helpers import _bearer_client, _delegated_client, _make_activity


class ActivityDetailViewTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(email="athlete@example.cc", password="x", name="Athlete")
        self.outsider = User.objects.create_user(email="outsider@example.cc", password="x", name="Outsider")

    def test_get_detail(self):
        activity = _make_activity(self.athlete)
        response = _bearer_client(self.athlete).get(f"/v1/activities/{activity.id}")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["id"], activity.id)

    def test_get_detail_not_found(self):
        response = _bearer_client(self.athlete).get("/v1/activities/act_doesnotexist")
        self.assertEqual(response.status_code, 404)

    def test_outsider_forbidden_on_detail(self):
        activity = _make_activity(self.athlete)
        client = _delegated_client(self.outsider, self.athlete, scopes=["activities:read"])
        response = client.get(f"/v1/activities/{activity.id}")
        self.assertEqual(response.status_code, 403)

    def test_patch_rename_and_sport(self):
        activity = _make_activity(self.athlete)
        response = _bearer_client(self.athlete).patch(
            f"/v1/activities/{activity.id}", {"name": "Renamed", "sport": "bike"}, format="json"
        )
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["name"], "Renamed")
        self.assertEqual(response.json()["sport"], "bike")

    def test_patch_link_workout(self):
        activity = _make_activity(self.athlete)
        workout = Workout.objects.create(created_by=self.athlete, name="Z2 long ride", sport="bike")
        response = _bearer_client(self.athlete).patch(
            f"/v1/activities/{activity.id}", {"workout_id": workout.id}, format="json"
        )
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["workout_id"], workout.id)

    def test_patch_link_workout_belonging_to_someone_else_404(self):
        activity = _make_activity(self.athlete)
        other_athlete = User.objects.create_user(email="other2@example.cc", password="x", name="Other2")
        workout = Workout.objects.create(created_by=other_athlete, name="Not mine", sport="bike")
        response = _bearer_client(self.athlete).patch(
            f"/v1/activities/{activity.id}", {"workout_id": workout.id}, format="json"
        )
        self.assertEqual(response.status_code, 404)

    def test_patch_unlink_workout(self):
        workout = Workout.objects.create(created_by=self.athlete, name="Z2 long ride", sport="bike")
        activity = _make_activity(self.athlete, workout=workout)
        response = _bearer_client(self.athlete).patch(
            f"/v1/activities/{activity.id}", {"workout_id": None}, format="json"
        )
        self.assertEqual(response.status_code, 200)
        self.assertIsNone(response.json()["workout_id"])

    def test_patch_weights_and_fluids(self):
        activity = _make_activity(self.athlete)
        response = _bearer_client(self.athlete).patch(
            f"/v1/activities/{activity.id}",
            {"start_weight_kg": 72.5, "end_weight_kg": 71.0, "fluids_ml": 750},
            format="json",
        )
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertEqual(data["start_weight_kg"], 72.5)
        self.assertEqual(data["end_weight_kg"], 71.0)
        self.assertEqual(data["fluids_ml"], 750)

    def test_patch_avg_air_temp_and_humidity_manual_on_bike(self):
        activity = _make_activity(self.athlete, sport="bike")
        response = _bearer_client(self.athlete).patch(
            f"/v1/activities/{activity.id}",
            {"avg_air_temp": 18.5, "avg_humidity": 45},
            format="json",
        )
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertEqual(data["avg_air_temp"], 18.5)
        self.assertEqual(data["avg_humidity"], 45)

    def test_patch_avg_air_temp_manual_on_run_without_stryd_data(self):
        activity = _make_activity(self.athlete, sport="run")
        response = _bearer_client(self.athlete).patch(
            f"/v1/activities/{activity.id}", {"avg_air_temp": 15.0}, format="json"
        )
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["avg_air_temp"], 15.0)

    def test_patch_avg_air_temp_ignored_for_run_with_computed_stryd_data(self):
        activity = _make_activity(self.athlete, sport="run", avg_air_temp=21.5, avg_humidity=57)
        Record.objects.create(activity=activity, t=0, ts=activity.start_date, air_temp=21.5, humidity=57)

        response = _bearer_client(self.athlete).patch(
            f"/v1/activities/{activity.id}",
            {"avg_air_temp": 99.0, "avg_humidity": 1},
            format="json",
        )
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertEqual(data["avg_air_temp"], 21.5)
        self.assertEqual(data["avg_humidity"], 57)
        activity.refresh_from_db()
        self.assertEqual(activity.avg_air_temp, 21.5)
        self.assertEqual(activity.avg_humidity, 57)

    def test_viewer_cannot_patch(self):
        UserRelationship.objects.create(
            owner=self.athlete,
            grantee=self.outsider,
            role=UserRelationship.ROLE_VIEWER,
            status=UserRelationship.STATUS_ACTIVE,
        )
        activity = _make_activity(self.athlete)
        client = _delegated_client(self.outsider, self.athlete, scopes=["activities:read"])
        response = client.patch(f"/v1/activities/{activity.id}", {"name": "Nope"}, format="json")
        self.assertEqual(response.status_code, 403)

    def test_coach_can_patch(self):
        UserRelationship.objects.create(
            owner=self.athlete,
            grantee=self.outsider,
            role=UserRelationship.ROLE_COACH,
            status=UserRelationship.STATUS_ACTIVE,
        )
        activity = _make_activity(self.athlete)
        client = _delegated_client(self.outsider, self.athlete, scopes=["coach"])
        response = client.patch(f"/v1/activities/{activity.id}", {"name": "Coached edit"}, format="json")
        self.assertEqual(response.status_code, 200)

    def test_delete_activity_cascades_laps_and_tags(self):
        activity = _make_activity(self.athlete)
        Lap.objects.create(activity=activity, index=1, duration=300, distance_km=1.0)
        tag = Tag.objects.create(athlete=self.athlete, name="Race")
        ActivityTag.objects.create(activity=activity, tag=tag)

        response = _bearer_client(self.athlete).delete(f"/v1/activities/{activity.id}")
        self.assertEqual(response.status_code, 204)
        self.assertFalse(Activity.objects.filter(pk=activity.id).exists())
        self.assertFalse(Lap.objects.filter(activity_id=activity.id).exists())
        self.assertFalse(ActivityTag.objects.filter(activity_id=activity.id).exists())
        # The tag itself (athlete-owned, possibly used elsewhere) survives.
        self.assertTrue(Tag.objects.filter(pk=tag.id).exists())

    def test_outsider_cannot_delete(self):
        activity = _make_activity(self.athlete)
        client = _delegated_client(self.outsider, self.athlete, scopes=["activities:read"])
        response = client.delete(f"/v1/activities/{activity.id}")
        self.assertEqual(response.status_code, 403)


class ActivityThresholdHistoryFieldTests(TestCase):
    """threshold_history's two distinct signals - own effort vs. revealed by this activity's
    own ingest pass - see ActivitySerializer.get_threshold_history."""

    def setUp(self):
        self.athlete = User.objects.create_user(email="threshold-field@example.cc", password="x", name="Athlete")

    def test_own_effort_entry_is_flagged_with_its_own_activity_id(self):
        activity = _make_activity(self.athlete, start_date=datetime(2026, 1, 1, 7, 0, tzinfo=UTC))
        ThresholdHistory.objects.create(
            athlete=self.athlete,
            field="ftp",
            value_numeric=250,
            source_activity=activity,
            effective_from=date(2026, 1, 1),
            current_from=date(2026, 1, 1),
        )

        response = _bearer_client(self.athlete).get(f"/v1/activities/{activity.id}")

        entries = response.json()["threshold_history"]
        self.assertEqual(len(entries), 1)
        self.assertEqual(entries[0]["field"], "ftp")
        self.assertEqual(entries[0]["value"], 250)
        self.assertTrue(entries[0]["is_current"])
        self.assertEqual(entries[0]["source_activity_id"], activity.id)

    def test_an_activity_that_reveals_a_different_dormant_effort_is_flagged_with_that_efforts_activity_id(self):
        # The exact real-world scenario current_from exists for: `better` stays current until it
        # ages out, and `trigger`'s own ingest is what notices `worse` has become current - even
        # though `worse` isn't `trigger`'s own effort at all.
        better = _make_activity(self.athlete, name="Lee Valley", start_date=datetime(2023, 8, 26, 8, 45, tzinfo=UTC))
        worse = _make_activity(self.athlete, name="Big Half", start_date=datetime(2023, 9, 3, 7, 45, tzinfo=UTC))
        trigger = _make_activity(
            self.athlete, name="Run on 2023-12-22", start_date=datetime(2023, 12, 22, 7, 0, tzinfo=UTC)
        )
        ThresholdHistory.objects.create(
            athlete=self.athlete,
            field="threshold_pace",
            value_pace="4:20",
            source_activity=better,
            effective_from=date(2023, 8, 26),
            current_from=date(2023, 8, 26),
        )
        ThresholdHistory.objects.create(
            athlete=self.athlete,
            field="threshold_pace",
            value_pace="4:26",
            source_activity=worse,
            effective_from=date(2023, 9, 3),
            current_from=date(2023, 12, 22),
        )

        response = _bearer_client(self.athlete).get(f"/v1/activities/{trigger.id}")

        entries = response.json()["threshold_history"]
        self.assertEqual(len(entries), 1)
        self.assertEqual(entries[0]["field"], "threshold_pace")
        self.assertEqual(entries[0]["value"], "4:26")
        self.assertEqual(entries[0]["source_activity_id"], worse.id)  # not trigger.id

        # The revealed activity's own page shows the same row as its own effort, not a reveal.
        worse_response = _bearer_client(self.athlete).get(f"/v1/activities/{worse.id}")
        worse_entries = worse_response.json()["threshold_history"]
        self.assertEqual(len(worse_entries), 1)
        self.assertEqual(worse_entries[0]["source_activity_id"], worse.id)

        # `better`'s own page is unaffected - it was superseded, not a reveal trigger.
        better_response = _bearer_client(self.athlete).get(f"/v1/activities/{better.id}")
        better_entries = better_response.json()["threshold_history"]
        self.assertEqual(len(better_entries), 1)
        self.assertEqual(better_entries[0]["source_activity_id"], better.id)
        self.assertFalse(better_entries[0]["is_current"])
