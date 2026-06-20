import datetime

from django.test import Client, TestCase
from django.utils import timezone
from oauth2_provider.models import get_application_model, get_grant_model
from rest_framework.test import APIClient

from accounts.models import User, UserRelationship
from authn.jwt_utils import decode_jwt, mint_jwt
from authn.oauth_utils import issue_token_pair
from core.permissions import user_may_read, user_may_write


def _bearer_client(user, scope="activities:read activities:write workouts:write calendar:write coach gear:write"):
    access_token, _ = issue_token_pair(user, scope=scope)
    client = APIClient()
    client.credentials(HTTP_AUTHORIZATION=f"Bearer {access_token.token}")
    return client


class JwksViewTests(TestCase):
    def test_jwks_is_public_and_round_trips_with_minted_tokens(self):
        response = APIClient().get("/.well-known/jwks.json")
        self.assertEqual(response.status_code, 200)
        keys = response.json()["keys"]
        self.assertEqual(len(keys), 1)
        self.assertEqual(keys[0]["kty"], "RSA")
        self.assertEqual(keys[0]["alg"], "RS256")

        token, claims = mint_jwt(sub="usr_test", athlete_id="usr_test", scopes=["activities:read"], expires_in=60)
        decoded = decode_jwt(token)
        self.assertEqual(decoded["sub"], claims["sub"])


class CreateJwtViewTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(email="athlete@example.cc", password="s3cret-pass", name="Athlete")
        self.coach = User.objects.create_user(email="coach@example.cc", password="s3cret-pass", name="Coach")
        self.outsider = User.objects.create_user(email="outsider@example.cc", password="s3cret-pass", name="Outsider")

    def test_requires_authentication(self):
        response = APIClient().post("/v1/auth/jwt", {}, format="json")
        self.assertEqual(response.status_code, 401)

    def test_defaults_to_self_with_read_scope(self):
        response = _bearer_client(self.athlete).post("/v1/auth/jwt", {}, format="json")
        self.assertEqual(response.status_code, 201)
        claims = response.json()["claims"]
        self.assertEqual(claims["sub"], self.athlete.id)
        self.assertEqual(claims["athlete_id"], self.athlete.id)
        self.assertEqual(claims["scope"], "activities:read")

    def test_expires_in_above_max_is_rejected(self):
        response = _bearer_client(self.athlete).post("/v1/auth/jwt", {"expires_in": 999999}, format="json")
        self.assertEqual(response.status_code, 400)

    def test_scopes_must_be_subset_of_callers_own_scopes(self):
        narrow_client = _bearer_client(self.athlete, scope="activities:read")
        response = narrow_client.post("/v1/auth/jwt", {"scopes": ["activities:write"]}, format="json")
        self.assertEqual(response.status_code, 400)

    def test_outsider_cannot_mint_jwt_for_athlete_without_relationship(self):
        response = _bearer_client(self.outsider).post("/v1/auth/jwt", {"athlete_id": self.athlete.id}, format="json")
        self.assertEqual(response.status_code, 403)

    def test_active_viewer_can_mint_read_only_jwt(self):
        UserRelationship.objects.create(
            owner=self.athlete,
            grantee=self.coach,
            role=UserRelationship.ROLE_VIEWER,
            status=UserRelationship.STATUS_ACTIVE,
        )
        response = _bearer_client(self.coach).post(
            "/v1/auth/jwt", {"athlete_id": self.athlete.id, "scopes": ["activities:read"]}, format="json"
        )
        self.assertEqual(response.status_code, 201)

    def test_active_viewer_cannot_mint_write_jwt(self):
        UserRelationship.objects.create(
            owner=self.athlete,
            grantee=self.coach,
            role=UserRelationship.ROLE_VIEWER,
            status=UserRelationship.STATUS_ACTIVE,
        )
        response = _bearer_client(self.coach).post(
            "/v1/auth/jwt", {"athlete_id": self.athlete.id, "scopes": ["calendar:write"]}, format="json"
        )
        self.assertEqual(response.status_code, 403)

    def test_active_coach_can_mint_write_jwt(self):
        UserRelationship.objects.create(
            owner=self.athlete,
            grantee=self.coach,
            role=UserRelationship.ROLE_COACH,
            status=UserRelationship.STATUS_ACTIVE,
        )
        response = _bearer_client(self.coach).post(
            "/v1/auth/jwt", {"athlete_id": self.athlete.id, "scopes": ["calendar:write"]}, format="json"
        )
        self.assertEqual(response.status_code, 201)
        self.assertEqual(response.json()["claims"]["sub"], self.coach.id)
        self.assertEqual(response.json()["claims"]["athlete_id"], self.athlete.id)

    def test_pending_relationship_does_not_grant_access(self):
        UserRelationship.objects.create(
            owner=self.athlete,
            grantee=self.coach,
            role=UserRelationship.ROLE_COACH,
            status=UserRelationship.STATUS_PENDING,
        )
        response = _bearer_client(self.coach).post("/v1/auth/jwt", {"athlete_id": self.athlete.id}, format="json")
        self.assertEqual(response.status_code, 403)


class DelegationPermissionMatrixTests(TestCase):
    """Direct unit tests of core.permissions' read/write rules per relationship state."""

    def setUp(self):
        self.athlete = User.objects.create_user(email="athlete@example.cc", password="x", name="Athlete")
        self.other = User.objects.create_user(email="other@example.cc", password="x", name="Other")

    def test_self_may_read_and_write_own_data(self):
        self.assertTrue(user_may_read(self.athlete.id, self.athlete.id))
        self.assertTrue(user_may_write(self.athlete.id, self.athlete.id))

    def test_no_relationship_denies_read_and_write(self):
        self.assertFalse(user_may_read(self.other.id, self.athlete.id))
        self.assertFalse(user_may_write(self.other.id, self.athlete.id))

    def test_active_viewer_may_read_not_write(self):
        UserRelationship.objects.create(
            owner=self.athlete,
            grantee=self.other,
            role=UserRelationship.ROLE_VIEWER,
            status=UserRelationship.STATUS_ACTIVE,
        )
        self.assertTrue(user_may_read(self.other.id, self.athlete.id))
        self.assertFalse(user_may_write(self.other.id, self.athlete.id))

    def test_active_coach_may_read_and_write(self):
        UserRelationship.objects.create(
            owner=self.athlete,
            grantee=self.other,
            role=UserRelationship.ROLE_COACH,
            status=UserRelationship.STATUS_ACTIVE,
        )
        self.assertTrue(user_may_read(self.other.id, self.athlete.id))
        self.assertTrue(user_may_write(self.other.id, self.athlete.id))

    def test_pending_coach_may_neither_read_nor_write(self):
        UserRelationship.objects.create(
            owner=self.athlete,
            grantee=self.other,
            role=UserRelationship.ROLE_COACH,
            status=UserRelationship.STATUS_PENDING,
        )
        self.assertFalse(user_may_read(self.other.id, self.athlete.id))
        self.assertFalse(user_may_write(self.other.id, self.athlete.id))


class OAuthTokenEndpointTests(TestCase):
    """Exercises the real /oauth/token view (both grant types from openapi.yaml),
    as opposed to the issue_token_pair() ORM bypass used elsewhere for speed.
    """

    def setUp(self):
        self.user = User.objects.create_user(email="athlete@example.cc", password="x", name="Athlete")
        Application = get_application_model()
        self.application = Application.objects.create(
            name="third-party-test-client",
            client_type=Application.CLIENT_CONFIDENTIAL,
            authorization_grant_type=Application.GRANT_AUTHORIZATION_CODE,
            redirect_uris="https://example.cc/callback",
            client_id="test-client-id",
            client_secret="test-client-secret",
        )

    def _create_grant(self, scope="activities:read"):
        Grant = get_grant_model()
        return Grant.objects.create(
            user=self.user,
            application=self.application,
            code="test-authorization-code",
            expires=timezone.now() + datetime.timedelta(minutes=5),
            redirect_uri="https://example.cc/callback",
            scope=scope,
        )

    def test_authorization_code_grant_issues_prefixed_tokens(self):
        self._create_grant()
        response = Client().post(
            "/oauth/token",
            {
                "grant_type": "authorization_code",
                "code": "test-authorization-code",
                "redirect_uri": "https://example.cc/callback",
                "client_id": "test-client-id",
                "client_secret": "test-client-secret",
            },
        )
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertTrue(data["access_token"].startswith("cad_at_"))
        self.assertTrue(data["refresh_token"].startswith("cad_rt_"))
        self.assertEqual(data["token_type"], "Bearer")

    def test_refresh_token_grant_rotates_and_reissues_prefixed_tokens(self):
        self._create_grant()
        issued = (
            Client()
            .post(
                "/oauth/token",
                {
                    "grant_type": "authorization_code",
                    "code": "test-authorization-code",
                    "redirect_uri": "https://example.cc/callback",
                    "client_id": "test-client-id",
                    "client_secret": "test-client-secret",
                },
            )
            .json()
        )

        response = Client().post(
            "/oauth/token",
            {
                "grant_type": "refresh_token",
                "refresh_token": issued["refresh_token"],
                "client_id": "test-client-id",
                "client_secret": "test-client-secret",
            },
        )
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertTrue(data["access_token"].startswith("cad_at_"))
        self.assertNotEqual(data["access_token"], issued["access_token"])
        self.assertTrue(data["refresh_token"].startswith("cad_rt_"))

    def test_invalid_client_secret_is_rejected(self):
        self._create_grant()
        response = Client().post(
            "/oauth/token",
            {
                "grant_type": "authorization_code",
                "code": "test-authorization-code",
                "redirect_uri": "https://example.cc/callback",
                "client_id": "test-client-id",
                "client_secret": "wrong-secret",
            },
        )
        self.assertEqual(response.status_code, 401)
