from pathlib import Path

from django.test import TestCase

from accounts.models import User
from activities.tests.helpers import _bearer_client, _delegated_client, _make_activity
from courses.models import Course

FIXTURES = Path(__file__).resolve().parent.parent / "fixtures"


class CourseUploadTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(email="athlete@example.cc", password="x", name="Athlete")
        self.outsider = User.objects.create_user(email="outsider@example.cc", password="x", name="Outsider")

    def test_upload_gpx_creates_course(self):
        with open(FIXTURES / "hill_loop.gpx", "rb") as f:
            response = _bearer_client(self.athlete).post(
                "/v1/courses", {"gpx": f, "name": "Hill Loop"}, format="multipart"
            )
        self.assertEqual(response.status_code, 201)
        data = response.json()
        self.assertEqual(data["name"], "Hill Loop")
        self.assertTrue(data["is_loop"])
        self.assertGreater(data["distance_km"], 0)
        self.assertEqual(Course.objects.filter(athlete=self.athlete).count(), 1)

    def test_upload_rejects_too_small_gpx(self):
        with open(FIXTURES / "too_small.gpx", "rb") as f:
            response = _bearer_client(self.athlete).post(
                "/v1/courses", {"gpx": f, "name": "Too Small"}, format="multipart"
            )
        self.assertEqual(response.status_code, 422)

    def test_upload_requires_name(self):
        with open(FIXTURES / "hill_loop.gpx", "rb") as f:
            response = _bearer_client(self.athlete).post("/v1/courses", {"gpx": f}, format="multipart")
        self.assertEqual(response.status_code, 400)

    def test_outsider_cannot_read_anothers_course(self):
        course = Course.objects.create(athlete=self.athlete, name="Mine", source="gpx")
        client = _delegated_client(self.outsider, self.athlete, ["activities:read"])
        # A delegated token naming this athlete, but with no real UserRelationship grant
        # behind it - user_may_read must still deny it.
        response = client.get(f"/v1/courses/{course.id}")
        self.assertEqual(response.status_code, 403)


class CourseFromActivityTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(email="athlete@example.cc", password="x", name="Athlete")

    def test_from_activity_requires_enough_gps_points(self):
        activity = _make_activity(self.athlete, has_gps=True)
        response = _bearer_client(self.athlete).post(
            "/v1/courses/from-activity", {"activity_id": activity.id, "name": "From Activity"}, format="json"
        )
        # The fixture activity has no Record rows at all.
        self.assertEqual(response.status_code, 422)

    def test_from_activity_rejects_mismatched_athlete(self):
        other = User.objects.create_user(email="other@example.cc", password="x", name="Other")
        activity = _make_activity(other)
        response = _bearer_client(self.athlete).post(
            "/v1/courses/from-activity", {"activity_id": activity.id, "name": "Not mine"}, format="json"
        )
        self.assertEqual(response.status_code, 403)


class CourseDetailTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(email="athlete@example.cc", password="x", name="Athlete")
        self.coach = User.objects.create_user(email="coach@example.cc", password="x", name="Coach")
        self.course = Course.objects.create(
            athlete=self.athlete, name="Original", source="gpx", distance_km=10.0, route_polyline=[[0, 0], [1, 1]]
        )

    def test_athlete_can_rename_own_course(self):
        response = _bearer_client(self.athlete).patch(
            f"/v1/courses/{self.course.id}", {"name": "Renamed"}, format="json"
        )
        self.assertEqual(response.status_code, 200)
        self.course.refresh_from_db()
        self.assertEqual(self.course.name, "Renamed")

    def test_coach_without_grant_cannot_write(self):
        client = _delegated_client(self.coach, self.athlete, ["activities:read", "activities:write"])
        response = client.patch(f"/v1/courses/{self.course.id}", {"name": "Hijacked"}, format="json")
        self.assertEqual(response.status_code, 403)

    def test_delete_removes_course(self):
        response = _bearer_client(self.athlete).delete(f"/v1/courses/{self.course.id}")
        self.assertEqual(response.status_code, 204)
        self.assertFalse(Course.objects.filter(pk=self.course.id).exists())

    def test_gpx_download_regenerates_when_no_original_file(self):
        response = _bearer_client(self.athlete).get(f"/v1/courses/{self.course.id}/gpx")
        self.assertEqual(response.status_code, 200)
        self.assertIn(b"<gpx", response.content)
