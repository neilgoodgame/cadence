from django.test import TestCase
from rest_framework.test import APIClient

from accounts.models import PersonalAccessToken, User, UserRelationship
from accounts.tokens import generate_secret, hash_secret, visible_prefix
from authn.oauth_utils import issue_token_pair


class UserManagerTests(TestCase):
    def test_create_user_hashes_password_and_assigns_prefixed_id(self):
        user = User.objects.create_user(email="neil@example.cc", password="s3cret-pass", name="Neil")
        self.assertTrue(user.id.startswith("usr_"))
        self.assertTrue(user.check_password("s3cret-pass"))
        self.assertFalse(user.is_staff)
        self.assertTrue(user.is_active)

    def test_create_user_without_password_is_unusable(self):
        user = User.objects.create_user(email="social@example.cc", name="Social Signup")
        self.assertFalse(user.has_usable_password())

    def test_create_superuser_sets_staff_and_superuser(self):
        admin = User.objects.create_superuser(email="admin@example.cc", password="s3cret-pass", name="Admin")
        self.assertTrue(admin.is_staff)
        self.assertTrue(admin.is_superuser)


def _bearer_client(user, scope="activities:read activities:write workouts:write calendar:write coach gear:write"):
    access_token, _ = issue_token_pair(user, scope=scope)
    client = APIClient()
    client.credentials(HTTP_AUTHORIZATION=f"Bearer {access_token.token}")
    return client


class RegisterViewTests(TestCase):
    def test_register_creates_user_and_token_pair(self):
        response = APIClient().post(
            "/v1/auth/register",
            {"name": "Neil Goodgame", "email": "neil@example.cc", "password": "s3cret-pass"},
            format="json",
        )
        self.assertEqual(response.status_code, 201)
        body = response.json()
        self.assertEqual(body["athlete"]["email"], "neil@example.cc")
        self.assertTrue(body["tokens"]["access_token"].startswith("cad_at_"))
        self.assertTrue(body["tokens"]["refresh_token"].startswith("cad_rt_"))
        self.assertTrue(User.objects.filter(email="neil@example.cc").exists())

    def test_register_duplicate_email_conflicts(self):
        User.objects.create_user(email="neil@example.cc", password="s3cret-pass", name="Neil")
        response = APIClient().post(
            "/v1/auth/register",
            {"name": "Other Neil", "email": "neil@example.cc", "password": "another-pass"},
            format="json",
        )
        self.assertEqual(response.status_code, 409)
        self.assertEqual(response.json()["error"]["type"], "conflict_error")

    def test_register_social_signup_is_stubbed(self):
        response = APIClient().post(
            "/v1/auth/register",
            {"name": "Social User", "provider": "google", "id_token": "fake-token"},
            format="json",
        )
        self.assertEqual(response.status_code, 400)


class LoginViewTests(TestCase):
    def test_login_with_correct_credentials_returns_token_pair(self):
        User.objects.create_user(email="neil@example.cc", password="s3cret-pass", name="Neil")
        response = APIClient().post(
            "/v1/auth/login", {"email": "neil@example.cc", "password": "s3cret-pass"}, format="json"
        )
        self.assertEqual(response.status_code, 200)
        body = response.json()
        self.assertEqual(body["athlete"]["email"], "neil@example.cc")
        self.assertTrue(body["tokens"]["access_token"].startswith("cad_at_"))
        self.assertTrue(body["tokens"]["refresh_token"].startswith("cad_rt_"))

    def test_login_with_wrong_password_is_unauthorized(self):
        User.objects.create_user(email="neil@example.cc", password="s3cret-pass", name="Neil")
        response = APIClient().post(
            "/v1/auth/login", {"email": "neil@example.cc", "password": "wrong-pass"}, format="json"
        )
        self.assertEqual(response.status_code, 401)
        self.assertEqual(response.json()["error"]["type"], "authentication_error")

    def test_login_with_unknown_email_is_unauthorized(self):
        response = APIClient().post(
            "/v1/auth/login", {"email": "nobody@example.cc", "password": "whatever-pass"}, format="json"
        )
        self.assertEqual(response.status_code, 401)

    def test_login_for_social_only_account_is_unauthorized(self):
        User.objects.create_user(email="social@example.cc", name="Social Signup")
        response = APIClient().post(
            "/v1/auth/login", {"email": "social@example.cc", "password": "anything-at-all"}, format="json"
        )
        self.assertEqual(response.status_code, 401)


class MeViewTests(TestCase):
    def test_requires_authentication(self):
        response = APIClient().get("/v1/me")
        self.assertEqual(response.status_code, 401)

    def test_returns_authenticated_user(self):
        user = User.objects.create_user(email="neil@example.cc", password="s3cret-pass", name="Neil")
        response = _bearer_client(user).get("/v1/me")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["id"], user.id)


class ContextsViewTests(TestCase):
    def test_returns_self_plus_coaching_and_coached_by(self):
        athlete = User.objects.create_user(email="athlete@example.cc", password="x", name="Athlete")
        coach = User.objects.create_user(email="coach@example.cc", password="x", name="Coach", handle="@coach")
        UserRelationship.objects.create(
            owner=athlete, grantee=coach, role=UserRelationship.ROLE_COACH, status=UserRelationship.STATUS_ACTIVE
        )

        coach_response = _bearer_client(coach).get("/v1/me/contexts")
        self.assertEqual(coach_response.status_code, 200)
        coaching = coach_response.json()["coaching"]
        self.assertEqual(len(coaching), 1)
        self.assertEqual(coaching[0]["user_id"], athlete.id)
        self.assertEqual(coaching[0]["role"], "coach")

        athlete_response = _bearer_client(athlete).get("/v1/me/contexts")
        coached_by = athlete_response.json()["coached_by"]
        self.assertEqual(len(coached_by), 1)
        self.assertEqual(coached_by[0]["handle"], "@coach")


class AccessTokenTests(TestCase):
    def setUp(self):
        self.user = User.objects.create_user(email="neil@example.cc", password="s3cret-pass", name="Neil")
        self.client = _bearer_client(self.user)

    def test_create_list_and_authenticate_with_pat(self):
        response = self.client.post(
            "/v1/auth/tokens", {"name": "Home server", "scopes": ["activities:read"]}, format="json"
        )
        self.assertEqual(response.status_code, 201)
        secret = response.json()["secret"]
        self.assertTrue(secret.startswith("cad_pat_"))

        listing = self.client.get("/v1/auth/tokens")
        self.assertEqual(len(listing.json()["data"]), 1)
        self.assertNotIn("secret", listing.json()["data"][0])

        pat_client = APIClient()
        pat_client.credentials(HTTP_AUTHORIZATION=f"Bearer {secret}")
        me_response = pat_client.get("/v1/me")
        self.assertEqual(me_response.status_code, 200)

    def test_create_token_cannot_exceed_caller_scopes(self):
        narrow_client = _bearer_client(self.user, scope="activities:read")
        response = narrow_client.post(
            "/v1/auth/tokens", {"name": "Too broad", "scopes": ["activities:write"]}, format="json"
        )
        self.assertEqual(response.status_code, 400)

    def test_revoke_token(self):
        secret = generate_secret()
        pat = PersonalAccessToken.objects.create(
            user=self.user,
            name="To revoke",
            prefix=visible_prefix(secret),
            hashed_secret=hash_secret(secret),
            scopes=["activities:read"],
        )
        response = self.client.delete(f"/v1/auth/tokens/{pat.id}")
        self.assertEqual(response.status_code, 204)
        self.assertFalse(PersonalAccessToken.objects.filter(pk=pat.id).exists())

    def test_rotate_token_issues_new_secret(self):
        create_response = self.client.post(
            "/v1/auth/tokens", {"name": "Rotating", "scopes": ["activities:read"]}, format="json"
        )
        token_id = create_response.json()["id"]
        old_secret = create_response.json()["secret"]

        rotate_response = self.client.post(f"/v1/auth/tokens/{token_id}/rotate")
        self.assertEqual(rotate_response.status_code, 200)
        new_secret = rotate_response.json()["secret"]
        self.assertNotEqual(old_secret, new_secret)

        old_client = APIClient()
        old_client.credentials(HTTP_AUTHORIZATION=f"Bearer {old_secret}")
        self.assertEqual(old_client.get("/v1/me").status_code, 401)

        new_client = APIClient()
        new_client.credentials(HTTP_AUTHORIZATION=f"Bearer {new_secret}")
        self.assertEqual(new_client.get("/v1/me").status_code, 200)


class ShareViewTests(TestCase):
    def setUp(self):
        self.owner = User.objects.create_user(email="owner@example.cc", password="x", name="Owner")
        self.friend = User.objects.create_user(email="friend@example.cc", password="x", name="Friend", handle="@friend")
        self.client = _bearer_client(self.owner)

    def test_invite_by_email_creates_pending_viewer_share(self):
        response = self.client.post("/v1/shares", {"invitee": "friend@example.cc", "role": "viewer"}, format="json")
        self.assertEqual(response.status_code, 201)
        body = response.json()
        self.assertEqual(body["name"], "Friend")
        self.assertEqual(body["role"], "viewer")
        self.assertEqual(body["status"], "pending")
        self.assertTrue(UserRelationship.objects.filter(owner=self.owner, grantee=self.friend).exists())

    def test_invite_by_handle(self):
        response = self.client.post("/v1/shares", {"invitee": "@friend", "role": "coach"}, format="json")
        self.assertEqual(response.status_code, 201)
        self.assertEqual(response.json()["role"], "coach")

    def test_invite_unknown_invitee_is_rejected(self):
        response = self.client.post("/v1/shares", {"invitee": "nobody@example.cc", "role": "viewer"}, format="json")
        self.assertEqual(response.status_code, 400)

    def test_invite_self_is_rejected(self):
        response = self.client.post("/v1/shares", {"invitee": "owner@example.cc", "role": "viewer"}, format="json")
        self.assertEqual(response.status_code, 400)

    def test_duplicate_invite_conflicts(self):
        UserRelationship.objects.create(owner=self.owner, grantee=self.friend, role=UserRelationship.ROLE_VIEWER)
        response = self.client.post("/v1/shares", {"invitee": "friend@example.cc", "role": "coach"}, format="json")
        self.assertEqual(response.status_code, 409)

    def test_list_shares(self):
        UserRelationship.objects.create(
            owner=self.owner,
            grantee=self.friend,
            role=UserRelationship.ROLE_VIEWER,
            status=UserRelationship.STATUS_ACTIVE,
        )
        response = self.client.get("/v1/shares")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(len(response.json()["data"]), 1)
        self.assertEqual(response.json()["data"][0]["handle"], "@friend")

    def test_update_share_role(self):
        rel = UserRelationship.objects.create(owner=self.owner, grantee=self.friend, role=UserRelationship.ROLE_VIEWER)
        response = self.client.patch(f"/v1/shares/{rel.id}", {"role": "coach"}, format="json")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["role"], "coach")
        rel.refresh_from_db()
        self.assertEqual(rel.role, UserRelationship.ROLE_COACH)

    def test_cannot_update_someone_elses_share(self):
        other_owner = User.objects.create_user(email="other@example.cc", password="x", name="Other")
        rel = UserRelationship.objects.create(owner=other_owner, grantee=self.friend, role=UserRelationship.ROLE_VIEWER)
        response = self.client.patch(f"/v1/shares/{rel.id}", {"role": "coach"}, format="json")
        self.assertEqual(response.status_code, 404)

    def test_delete_share_revokes_access(self):
        rel = UserRelationship.objects.create(owner=self.owner, grantee=self.friend, role=UserRelationship.ROLE_VIEWER)
        response = self.client.delete(f"/v1/shares/{rel.id}")
        self.assertEqual(response.status_code, 204)
        self.assertFalse(UserRelationship.objects.filter(pk=rel.id).exists())


class CoachViewTests(TestCase):
    def setUp(self):
        self.coach = User.objects.create_user(email="coach@example.cc", password="x", name="Coach")
        self.athlete = User.objects.create_user(email="athlete@example.cc", password="x", name="Athlete")
        self.client = _bearer_client(self.coach)

    def test_roster_lists_active_relationships_only(self):
        UserRelationship.objects.create(
            owner=self.athlete,
            grantee=self.coach,
            role=UserRelationship.ROLE_COACH,
            status=UserRelationship.STATUS_ACTIVE,
        )
        pending_athlete = User.objects.create_user(email="pending@example.cc", password="x", name="Pending")
        UserRelationship.objects.create(
            owner=pending_athlete,
            grantee=self.coach,
            role=UserRelationship.ROLE_COACH,
            status=UserRelationship.STATUS_PENDING,
        )

        response = self.client.get("/v1/coach/athletes")
        self.assertEqual(response.status_code, 200)
        data = response.json()["data"]
        self.assertEqual(len(data), 1)
        self.assertEqual(data[0]["athlete_id"], self.athlete.id)

    def test_coach_athlete_detail_requires_active_relationship(self):
        response = self.client.get(f"/v1/coach/athletes/{self.athlete.id}")
        self.assertEqual(response.status_code, 404)

    def test_coach_athlete_detail_returns_summary(self):
        UserRelationship.objects.create(
            owner=self.athlete,
            grantee=self.coach,
            role=UserRelationship.ROLE_COACH,
            status=UserRelationship.STATUS_ACTIVE,
        )
        response = self.client.get(f"/v1/coach/athletes/{self.athlete.id}")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["athlete_id"], self.athlete.id)
