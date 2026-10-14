from pathlib import Path
from types import SimpleNamespace
from typing import Any

from django.test import TestCase

from accounts.models import User
from authn.oauth_utils import issue_token_pair
from courses.mcp import CourseMCPTools
from courses.models import Course
from courses.processing import parse_gpx_track, process_track_points

FIXTURES = Path(__file__).resolve().parent.parent / "fixtures"


def _mcp_request(user: User, scope: str) -> Any:
    access_token, _ = issue_token_pair(user, scope=scope)
    return SimpleNamespace(user=user, auth=access_token)


class GetCourseToolTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(email="mcp-course@example.cc", password="x", name="Athlete")
        self.tools = CourseMCPTools(request=_mcp_request(self.athlete, "activities:read"))

    def test_returns_sections_and_the_climbs_among_them(self):
        fields = process_track_points(parse_gpx_track((FIXTURES / "hill_loop.gpx").read_bytes()))
        course = Course.objects.create(athlete=self.athlete, name="Hill Loop", source="gpx", **fields)

        result = self.tools.get_course(course.id)

        self.assertEqual(result["elevation_quality"], "ok")
        self.assertEqual([s["type"] for s in result["sections"]], ["steep_climb", "flat", "steep_descent"])
        self.assertEqual([c["gain_m"] for c in result["climbs"]], [25])

    def test_a_course_with_no_elevation_source_reports_missing(self):
        course = Course.objects.create(athlete=self.athlete, name="Orphan", source="activity")

        result = self.tools.get_course(course.id)

        self.assertEqual(result["elevation_quality"], "missing")
        self.assertEqual(result["sections"], [])
