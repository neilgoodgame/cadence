from datetime import date, timedelta
from types import SimpleNamespace
from typing import Any

from django.test import TestCase
from rest_framework.exceptions import PermissionDenied, ValidationError

from accounts.delegation import create_virtual_coach
from accounts.models import PersonalAccessToken, User, UserRelationship
from accounts.tokens import generate_secret, hash_secret, visible_prefix
from authn.oauth_utils import issue_token_pair

from .mcp import AthleteMCPTools
from .models import ThresholdHistory


def _mcp_request(user: User, scope: str) -> Any:
    access_token, _ = issue_token_pair(user, scope=scope)
    return SimpleNamespace(user=user, auth=access_token)


def _delegated_mcp_request(coach: User, token: PersonalAccessToken) -> Any:
    return SimpleNamespace(user=coach, auth=token)


class SetAthleteThresholdsToolTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(email="mcp-athlete@example.cc", password="x", name="Athlete")

    def test_self_can_set_ftp(self):
        tools = AthleteMCPTools(request=_mcp_request(self.athlete, "activities:read activities:write"))

        result = tools.set_athlete_thresholds(ftp=280)

        self.assertEqual(result["ftp"]["value"], 280)
        entry = ThresholdHistory.objects.get(athlete=self.athlete, field="ftp")
        self.assertEqual(entry.set_by_id, self.athlete.id)
        self.assertIsNone(entry.source_activity_id)

    def test_only_passed_fields_are_changed(self):
        tools = AthleteMCPTools(request=_mcp_request(self.athlete, "activities:read activities:write"))
        tools.set_athlete_thresholds(ftp=280, threshold_pace="4:00")

        tools.set_athlete_thresholds(ftp=290)

        self.assertEqual(ThresholdHistory.objects.filter(athlete=self.athlete, field="threshold_pace").count(), 1)
        entry = ThresholdHistory.objects.filter(athlete=self.athlete, field="ftp").order_by("-effective_from").first()
        self.assertEqual(entry.value_numeric, 290)

    def test_no_fields_is_rejected(self):
        tools = AthleteMCPTools(request=_mcp_request(self.athlete, "activities:read activities:write"))

        with self.assertRaises(ValidationError):
            tools.set_athlete_thresholds()

    def test_coach_can_set_thresholds_on_behalf_of_the_athlete(self):
        created = create_virtual_coach(self.athlete, "Coach", ["activities:read", "activities:write"])
        token = created["token"]
        tools = AthleteMCPTools(request=_delegated_mcp_request(token.user, token))

        result = tools.set_athlete_thresholds(ftp=280)

        self.assertEqual(result["ftp"]["value"], 280)
        entry = ThresholdHistory.objects.get(athlete=self.athlete, field="ftp")
        self.assertEqual(entry.set_by_id, token.user_id)
        self.assertNotEqual(entry.set_by_id, self.athlete.id)

    def test_viewer_role_cannot_set_thresholds(self):
        viewer = User.objects.create_user(email="mcp-viewer@example.cc", password="x", name="Viewer")
        UserRelationship.objects.create(
            owner=self.athlete, grantee=viewer, role=UserRelationship.ROLE_VIEWER, status=UserRelationship.STATUS_ACTIVE
        )
        secret = generate_secret()
        token = PersonalAccessToken.objects.create(
            user=viewer,
            name="Viewer token",
            prefix=visible_prefix(secret),
            hashed_secret=hash_secret(secret),
            scopes=["activities:read", "activities:write"],
            delegated_athlete=self.athlete,
        )
        tools = AthleteMCPTools(request=_delegated_mcp_request(viewer, token))

        with self.assertRaises(PermissionDenied):
            tools.set_athlete_thresholds(ftp=280)

    def test_get_athlete_thresholds_reflects_set_by(self):
        set_tools = AthleteMCPTools(request=_mcp_request(self.athlete, "activities:read activities:write"))
        set_tools.set_athlete_thresholds(ftp=280)

        get_tools = AthleteMCPTools(request=_mcp_request(self.athlete, "activities:read"))
        result = get_tools.get_athlete_thresholds()

        self.assertEqual(result["ftp"]["set_by_user_id"], self.athlete.id)

    # --- Backdating (issue #493) ---

    def test_backdated_edit_reports_threshold_edit_results(self):
        tools = AthleteMCPTools(request=_mcp_request(self.athlete, "activities:read activities:write"))
        today = date.today()

        result = tools.set_athlete_thresholds(ftp=255, effective_from=(today - timedelta(days=10)).isoformat())

        edit = result["threshold_edit_results"]["ftp"]
        self.assertTrue(edit["changed"])
        self.assertTrue(edit["became_current"])
        self.assertFalse(edit["is_correction_only"])
        entry = ThresholdHistory.objects.get(athlete=self.athlete, field="ftp")
        self.assertEqual(entry.effective_from, today - timedelta(days=10))

    def test_backdated_edit_with_a_later_row_is_correction_only(self):
        tools = AthleteMCPTools(request=_mcp_request(self.athlete, "activities:read activities:write"))
        today = date.today()
        tools.set_athlete_thresholds(ftp=255)  # today, the later row

        result = tools.set_athlete_thresholds(ftp=247, effective_from=(today - timedelta(days=10)).isoformat())

        edit = result["threshold_edit_results"]["ftp"]
        self.assertFalse(edit["became_current"])
        self.assertTrue(edit["is_correction_only"])
        self.assertEqual(result["ftp"]["value"], 255)  # unchanged - the later row is still current

    def test_preview_does_not_persist(self):
        tools = AthleteMCPTools(request=_mcp_request(self.athlete, "activities:read activities:write"))

        result = tools.set_athlete_thresholds(ftp=280, preview=True)

        self.assertFalse(ThresholdHistory.objects.filter(athlete=self.athlete, field="ftp").exists())
        edit = result["threshold_edit_results"]["ftp"]
        self.assertTrue(edit["changed"])
        self.assertEqual(result["ftp"]["value"], None)  # unaffected - nothing was saved

    def test_future_effective_from_is_rejected(self):
        tools = AthleteMCPTools(request=_mcp_request(self.athlete, "activities:read activities:write"))
        tomorrow = (date.today() + timedelta(days=1)).isoformat()

        with self.assertRaises(ValidationError):
            tools.set_athlete_thresholds(ftp=280, effective_from=tomorrow)
