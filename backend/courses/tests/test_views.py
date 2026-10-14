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


class CourseSectionsApiTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(email="sections-athlete@example.cc", password="x", name="Athlete")
        self.client_ = _bearer_client(self.athlete)

    def _upload(self) -> dict:
        with open(FIXTURES / "hill_loop.gpx", "rb") as f:
            response = self.client_.post("/v1/courses", {"gpx": f, "name": "Hill Loop"}, format="multipart")
        self.assertEqual(response.status_code, 201)
        return response.json()

    def test_a_new_course_has_sections_and_climbs_from_them(self):
        data = self._upload()
        self.assertEqual(data["elevation_quality"], "ok")
        self.assertNotIn("elevation_series", data)
        types = [s["type"] for s in data["sections"]]
        self.assertEqual(types, ["steep_climb", "flat", "steep_descent"])
        self.assertEqual(data["sections"][0]["start_km"], 0.0)
        self.assertEqual([c["gain_m"] for c in data["climbs"]], [25])

    def test_preview_overrides_apply_to_this_response_only(self):
        course_id = self._upload()["id"]
        response = self.client_.get(f"/v1/courses/{course_id}?steep_min_pct=5")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["sections"][0]["type"], "climb")
        self.athlete.refresh_from_db()
        self.assertEqual(self.athlete.section_steep_min_pct, 4.0)
        self.assertEqual(self.client_.get(f"/v1/courses/{course_id}").json()["sections"][0]["type"], "steep_climb")

    def test_invalid_preview_overrides_are_rejected(self):
        course_id = self._upload()["id"]
        self.assertEqual(self.client_.get(f"/v1/courses/{course_id}?fit_tolerance_m=0.1").status_code, 400)
        self.assertEqual(self.client_.get(f"/v1/courses/{course_id}?fit_tolerance_m=abc").status_code, 400)
        self.assertEqual(self.client_.get(f"/v1/courses/{course_id}?algorithm=kmeans").status_code, 400)

    def test_the_athletes_saved_settings_apply_to_every_read(self):
        course_id = self._upload()["id"]
        response = self.client_.patch(
            f"/v1/athletes/{self.athlete.id}",
            {"course_section_settings": {"steep_min_pct": 5.0, "algorithm": "bottom_up"}},
            format="json",
        )
        self.assertEqual(response.status_code, 200)
        settings = response.json()["course_section_settings"]
        self.assertEqual(settings["steep_min_pct"], 5.0)
        self.assertEqual(settings["algorithm"], "bottom_up")
        self.assertEqual(settings["flat_max_pct"], 1.0)
        self.assertEqual(self.client_.get(f"/v1/courses/{course_id}").json()["sections"][0]["type"], "climb")

    def test_invalid_saved_settings_are_rejected_and_nothing_changes(self):
        response = self.client_.patch(
            f"/v1/athletes/{self.athlete.id}",
            {"course_section_settings": {"flat_max_pct": 3.0, "steep_min_pct": 2.5}},
            format="json",
        )
        self.assertEqual(response.status_code, 400)
        self.athlete.refresh_from_db()
        self.assertEqual(self.athlete.section_flat_max_pct, 1.0)

    def test_profile_reports_the_default_settings(self):
        settings = self.client_.get(f"/v1/athletes/{self.athlete.id}").json()["course_section_settings"]
        self.assertEqual(settings["algorithm"], "douglas_peucker")
        self.assertEqual(settings["min_section_m"], 250.0)

    def test_a_course_from_before_sections_is_filled_in_on_first_read(self):
        course_id = self._upload()["id"]
        Course.objects.filter(pk=course_id).update(elevation_quality="", elevation_series=[])
        data = self.client_.get(f"/v1/courses/{course_id}").json()
        self.assertEqual(data["elevation_quality"], "ok")
        self.assertEqual([c["gain_m"] for c in data["climbs"]], [25])
        self.assertGreater(len(Course.objects.get(pk=course_id).elevation_series), 200)

    def test_a_course_with_no_source_left_is_marked_missing(self):
        course = Course.objects.create(athlete=self.athlete, name="Orphan", source="activity")
        data = self.client_.get(f"/v1/courses/{course.id}").json()
        self.assertEqual(data["elevation_quality"], "missing")
        self.assertEqual(data["sections"], [])
        self.assertEqual(data["climbs"], [])

    def test_recompute_rebuilds_from_the_gpx(self):
        course_id = self._upload()["id"]
        Course.objects.filter(pk=course_id).update(elevation_quality="missing", elevation_series=[])
        response = self.client_.post(f"/v1/courses/{course_id}/recompute-elevation")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["elevation_quality"], "ok")

    def test_recompute_without_source_data_is_unprocessable(self):
        course = Course.objects.create(athlete=self.athlete, name="Orphan", source="activity")
        self.assertEqual(self.client_.post(f"/v1/courses/{course.id}/recompute-elevation").status_code, 422)

    def test_athlete_wide_recompute_counts_updated_and_skipped(self):
        self._upload()
        Course.objects.create(athlete=self.athlete, name="Orphan", source="activity")
        response = self.client_.post(f"/v1/athletes/{self.athlete.id}/recompute-elevation")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json(), {"updated": 1, "skipped": 1})

    def test_outsider_cannot_recompute(self):
        course_id = self._upload()["id"]
        outsider = User.objects.create_user(email="sections-outsider@example.cc", password="x", name="Outsider")
        client = _delegated_client(outsider, self.athlete, ["activities:read", "activities:write"])
        self.assertEqual(client.post(f"/v1/courses/{course_id}/recompute-elevation").status_code, 403)
        self.assertEqual(client.post(f"/v1/athletes/{self.athlete.id}/recompute-elevation").status_code, 403)
