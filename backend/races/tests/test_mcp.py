from types import SimpleNamespace
from typing import Any

from django.test import TestCase
from rest_framework.exceptions import PermissionDenied, ValidationError

from accounts.models import User
from authn.oauth_utils import issue_token_pair
from races.mcp import RaceMCPTools
from races.models import Race


def _mcp_request(user: User, scope: str) -> Any:
    access_token, _ = issue_token_pair(user, scope=scope)
    return SimpleNamespace(user=user, auth=access_token)


class CreateRaceToolTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(email="mcp-race@example.cc", password="x", name="Athlete")
        self.tools = RaceMCPTools(request=_mcp_request(self.athlete, "calendar:write"))

    def test_creates_an_a_race_with_no_block_plan(self):
        result = self.tools.create_race(name="Goal Marathon", date="2026-10-12", sport="run", distance_km=42.195)

        self.assertEqual(result["name"], "Goal Marathon")
        self.assertEqual(result["priority"], "A")
        self.assertEqual(result["block_weeks"], 16)
        self.assertIsNone(result["block_weeks_requested"])

    def test_explicit_block_plan_round_trips(self):
        result = self.tools.create_race(
            name="Goal Marathon",
            date="2026-10-12",
            sport="run",
            block_weeks_requested=20,
            base_weeks_requested=8,
            build_weeks_requested=7,
            peak_weeks_requested=2,
            taper_weeks_requested=3,
        )

        self.assertEqual(result["block_weeks_requested"], 20)
        self.assertEqual(result["block_weeks"], 20)
        race = Race.objects.get(pk=result["id"])
        self.assertEqual(race.base_weeks_requested, 8)

    def test_phase_weeks_not_summing_to_block_weeks_are_rejected(self):
        with self.assertRaises(ValidationError):
            self.tools.create_race(
                name="Goal Marathon",
                date="2026-10-12",
                block_weeks_requested=20,
                base_weeks_requested=5,
                build_weeks_requested=5,
                peak_weeks_requested=2,
                taper_weeks_requested=3,
            )

    def test_block_plan_fields_are_rejected_on_a_bc_race(self):
        with self.assertRaises(ValidationError):
            self.tools.create_race(name="Tune-up Half", date="2026-09-14", priority="B", block_weeks_requested=10)

    def test_requires_write_scope(self):
        tools = RaceMCPTools(request=_mcp_request(self.athlete, "activities:read"))
        with self.assertRaises(PermissionDenied):
            tools.create_race(name="Goal Marathon", date="2026-10-12")
