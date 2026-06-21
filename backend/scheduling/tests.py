from datetime import UTC, date, datetime

from django.test import TestCase
from rest_framework.test import APIClient

from accounts.models import User, UserRelationship
from activities.models import Activity
from authn.jwt_utils import mint_jwt
from authn.oauth_utils import issue_token_pair
from workouts.models import Workout

from .models import ScheduledWorkout


def _bearer_client(user, scope="activities:read activities:write workouts:write calendar:write coach"):
    access_token, _ = issue_token_pair(user, scope=scope)
    client = APIClient()
    client.credentials(HTTP_AUTHORIZATION=f"Bearer {access_token.token}")
    return client


def _delegated_client(sub, athlete_id, scopes):
    token, _claims = mint_jwt(sub=sub.id, athlete_id=athlete_id.id, scopes=scopes, expires_in=60)
    client = APIClient()
    client.credentials(HTTP_AUTHORIZATION=f"Bearer {token}")
    return client


class ScheduledWorkoutCreateTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(email="athlete@example.cc", password="x", name="Athlete")
        self.coach = User.objects.create_user(email="coach@example.cc", password="x", name="Coach")
        self.outsider = User.objects.create_user(email="outsider@example.cc", password="x", name="Outsider")
        self.workout = Workout.objects.create(created_by=self.athlete, name="Z2 long ride", sport="bike")

    def test_self_schedule_has_no_assigned_by(self):
        response = _bearer_client(self.athlete).post(
            "/v1/scheduled-workouts",
            {"workout_id": self.workout.id, "athlete_id": self.athlete.id, "date": "2026-06-20"},
            format="json",
        )
        self.assertEqual(response.status_code, 201)
        body = response.json()
        self.assertEqual(body["workout_id"], self.workout.id)
        self.assertEqual(body["athlete_id"], self.athlete.id)
        self.assertIsNone(body["assigned_by_id"])
        self.assertEqual(body["status"], "planned")

    def test_coach_can_assign_onto_athlete_plan(self):
        UserRelationship.objects.create(
            owner=self.athlete,
            grantee=self.coach,
            role=UserRelationship.ROLE_COACH,
            status=UserRelationship.STATUS_ACTIVE,
        )
        client = _delegated_client(self.coach, self.athlete, scopes=["coach"])
        response = client.post(
            "/v1/scheduled-workouts",
            {
                "workout_id": self.workout.id,
                "athlete_id": self.athlete.id,
                "date": "2026-06-21",
                "time_of_day": "AM",
            },
            format="json",
        )
        self.assertEqual(response.status_code, 201)
        body = response.json()
        self.assertEqual(body["assigned_by_id"], self.coach.id)
        self.assertEqual(body["time_of_day"], "AM")

    def test_outsider_without_relationship_forbidden(self):
        client = _delegated_client(self.outsider, self.athlete, scopes=["activities:read"])
        response = client.post(
            "/v1/scheduled-workouts",
            {"workout_id": self.workout.id, "athlete_id": self.athlete.id, "date": "2026-06-20"},
            format="json",
        )
        self.assertEqual(response.status_code, 403)

    def test_viewer_cannot_schedule(self):
        UserRelationship.objects.create(
            owner=self.athlete,
            grantee=self.outsider,
            role=UserRelationship.ROLE_VIEWER,
            status=UserRelationship.STATUS_ACTIVE,
        )
        client = _delegated_client(self.outsider, self.athlete, scopes=["activities:read"])
        response = client.post(
            "/v1/scheduled-workouts",
            {"workout_id": self.workout.id, "athlete_id": self.athlete.id, "date": "2026-06-20"},
            format="json",
        )
        self.assertEqual(response.status_code, 403)

    def test_workout_belonging_to_someone_else_404(self):
        other = User.objects.create_user(email="other@example.cc", password="x", name="Other")
        foreign_workout = Workout.objects.create(created_by=other, name="Not mine", sport="run")
        response = _bearer_client(self.athlete).post(
            "/v1/scheduled-workouts",
            {"workout_id": foreign_workout.id, "athlete_id": self.athlete.id, "date": "2026-06-20"},
            format="json",
        )
        self.assertEqual(response.status_code, 404)


class ScheduledWorkoutDetailTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(email="athlete@example.cc", password="x", name="Athlete")
        self.outsider = User.objects.create_user(email="outsider@example.cc", password="x", name="Outsider")
        self.workout = Workout.objects.create(created_by=self.athlete, name="Z2 long ride", sport="bike")
        self.scheduled = ScheduledWorkout.objects.create(
            workout=self.workout, athlete=self.athlete, date=date(2026, 6, 20)
        )

    def test_reschedule_date(self):
        response = _bearer_client(self.athlete).patch(
            f"/v1/scheduled-workouts/{self.scheduled.id}", {"date": "2026-06-25"}, format="json"
        )
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["date"], "2026-06-25")

    def test_link_activity_marks_completed(self):
        activity = Activity.objects.create(
            athlete=self.athlete,
            sport="bike",
            name="Long ride",
            start_date=datetime(2026, 6, 20, 8, 0, tzinfo=UTC),
        )
        response = _bearer_client(self.athlete).patch(
            f"/v1/scheduled-workouts/{self.scheduled.id}", {"activity_id": activity.id}, format="json"
        )
        self.assertEqual(response.status_code, 200)
        body = response.json()
        self.assertEqual(body["activity_id"], activity.id)
        self.assertEqual(body["status"], "completed")

    def test_link_activity_belonging_to_someone_else_404(self):
        other = User.objects.create_user(email="other@example.cc", password="x", name="Other")
        foreign_activity = Activity.objects.create(
            athlete=other, sport="bike", name="Not mine", start_date=datetime(2026, 6, 20, 8, 0, tzinfo=UTC)
        )
        response = _bearer_client(self.athlete).patch(
            f"/v1/scheduled-workouts/{self.scheduled.id}", {"activity_id": foreign_activity.id}, format="json"
        )
        self.assertEqual(response.status_code, 404)

    def test_outsider_cannot_patch(self):
        client = _delegated_client(self.outsider, self.athlete, scopes=["activities:read"])
        response = client.patch(f"/v1/scheduled-workouts/{self.scheduled.id}", {"date": "2026-06-25"}, format="json")
        self.assertEqual(response.status_code, 403)

    def test_delete_unschedules(self):
        response = _bearer_client(self.athlete).delete(f"/v1/scheduled-workouts/{self.scheduled.id}")
        self.assertEqual(response.status_code, 204)
        self.assertFalse(ScheduledWorkout.objects.filter(pk=self.scheduled.id).exists())

    def test_outsider_cannot_delete(self):
        client = _delegated_client(self.outsider, self.athlete, scopes=["activities:read"])
        response = client.delete(f"/v1/scheduled-workouts/{self.scheduled.id}")
        self.assertEqual(response.status_code, 403)


class CalendarViewTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(email="athlete@example.cc", password="x", name="Athlete")
        self.coach = User.objects.create_user(email="coach@example.cc", password="x", name="Coach")
        self.outsider = User.objects.create_user(email="outsider@example.cc", password="x", name="Outsider")
        self.workout = Workout.objects.create(created_by=self.athlete, name="Z2 long ride", sport="bike")

    def test_requires_from_and_to(self):
        response = _bearer_client(self.athlete).get("/v1/calendar")
        self.assertEqual(response.status_code, 400)

    def test_lists_entries_within_range(self):
        ScheduledWorkout.objects.create(workout=self.workout, athlete=self.athlete, date=date(2026, 6, 10))
        ScheduledWorkout.objects.create(workout=self.workout, athlete=self.athlete, date=date(2026, 6, 20))
        ScheduledWorkout.objects.create(workout=self.workout, athlete=self.athlete, date=date(2026, 7, 1))

        response = _bearer_client(self.athlete).get("/v1/calendar?from=2026-06-01&to=2026-06-30")
        self.assertEqual(response.status_code, 200)
        dates = [e["date"] for e in response.json()["data"]]
        self.assertEqual(dates, ["2026-06-10", "2026-06-20"])

    def test_coach_can_view_athletes_calendar_via_query_param(self):
        UserRelationship.objects.create(
            owner=self.athlete,
            grantee=self.coach,
            role=UserRelationship.ROLE_COACH,
            status=UserRelationship.STATUS_ACTIVE,
        )
        ScheduledWorkout.objects.create(workout=self.workout, athlete=self.athlete, date=date(2026, 6, 10))

        response = _bearer_client(self.coach).get(
            f"/v1/calendar?from=2026-06-01&to=2026-06-30&athlete_id={self.athlete.id}"
        )
        self.assertEqual(response.status_code, 200)
        self.assertEqual(len(response.json()["data"]), 1)

    def test_outsider_cannot_view_via_query_param(self):
        response = _bearer_client(self.outsider).get(
            f"/v1/calendar?from=2026-06-01&to=2026-06-30&athlete_id={self.athlete.id}"
        )
        self.assertEqual(response.status_code, 403)

    def test_delegated_jwt_defaults_to_claimed_athlete(self):
        UserRelationship.objects.create(
            owner=self.athlete,
            grantee=self.coach,
            role=UserRelationship.ROLE_COACH,
            status=UserRelationship.STATUS_ACTIVE,
        )
        ScheduledWorkout.objects.create(workout=self.workout, athlete=self.athlete, date=date(2026, 6, 10))
        client = _delegated_client(self.coach, self.athlete, scopes=["coach"])
        response = client.get("/v1/calendar?from=2026-06-01&to=2026-06-30")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(len(response.json()["data"]), 1)
