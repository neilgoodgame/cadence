from datetime import timedelta
from io import StringIO
from unittest import mock

from django.core.management import CommandError, call_command
from django.test import TestCase
from django.utils import timezone
from rest_framework.test import APIClient

from accounts.models import EmailVerificationToken, PersonalAccessToken, User, UserRelationship
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

    def test_register_issues_an_unverified_user_and_a_verification_token(self):
        response = APIClient().post(
            "/v1/auth/register",
            {"name": "Neil Goodgame", "email": "neil-verify@example.cc", "password": "s3cret-pass"},
            format="json",
        )
        self.assertEqual(response.status_code, 201)
        self.assertFalse(response.json()["athlete"]["email_verified"])
        user = User.objects.get(email="neil-verify@example.cc")
        self.assertFalse(user.email_verified)
        self.assertTrue(EmailVerificationToken.objects.filter(user=user).exists())

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


class VirtualCoachTests(TestCase):
    """A virtual coach's delegated token needs to make GET /v1/workouts (a list endpoint,
    which resolves the athlete to query via get_effective_athlete_id) return the *athlete's*
    workouts, not an empty list of the coach's own."""

    def setUp(self):
        from workouts.models import Workout

        self.athlete = User.objects.create_user(email="virtual-coach-athlete@example.cc", password="x", name="Athlete")
        self.workout = Workout.objects.create(created_by=self.athlete, name="Z2 long ride", sport="bike")
        self.client = _bearer_client(self.athlete)

    def test_create_virtual_coach_lists_the_athletes_workouts(self):
        response = self.client.post(
            "/v1/shares/virtual-coach",
            {"name": "Claude.ai", "scopes": ["activities:read", "workouts:write", "calendar:write"]},
            format="json",
        )
        self.assertEqual(response.status_code, 201)
        body = response.json()
        self.assertEqual(body["share"]["role"], "coach")
        self.assertEqual(body["share"]["status"], "active")
        secret = body["token"]["secret"]
        self.assertTrue(secret.startswith("cad_pat_"))

        pat_client = APIClient()
        pat_client.credentials(HTTP_AUTHORIZATION=f"Bearer {secret}")
        listing = pat_client.get("/v1/workouts")
        self.assertEqual(listing.status_code, 200)
        self.assertEqual(listing.json()["data"][0]["id"], self.workout.id)

    def test_a_plain_self_scoped_token_never_sees_another_athletes_workouts(self):
        # An ordinary (non-delegated) personal access token, minted by an unrelated user with no
        # share to self.athlete - the list endpoint must not leak across athletes by default.
        coach = User.objects.create_user(email="virtual-coach-control-coach@example.cc", password="x", name="Coach")
        coach_client = _bearer_client(coach)
        created_token = coach_client.post(
            "/v1/auth/tokens", {"name": "Coach's own token", "scopes": ["activities:read"]}, format="json"
        ).json()

        pat_client = APIClient()
        pat_client.credentials(HTTP_AUTHORIZATION=f"Bearer {created_token['secret']}")
        listing = pat_client.get("/v1/workouts")
        self.assertEqual(listing.status_code, 200)
        self.assertEqual(listing.json()["data"], [])

    def test_revoking_the_share_invalidates_the_virtual_coachs_token(self):
        created = self.client.post(
            "/v1/shares/virtual-coach",
            {"name": "Claude.ai", "scopes": ["activities:read", "workouts:write"]},
            format="json",
        ).json()

        self.client.delete(f"/v1/shares/{created['share']['id']}")

        pat_client = APIClient()
        pat_client.credentials(HTTP_AUTHORIZATION=f"Bearer {created['token']['secret']}")
        self.assertEqual(pat_client.get("/v1/workouts").status_code, 401)

    def test_a_viewer_only_relationship_cannot_mint_a_delegated_token_with_write_scopes(self):
        coach = User.objects.create_user(email="delegation-viewer-coach@example.cc", password="x", name="Coach")
        UserRelationship.objects.create(
            owner=self.athlete, grantee=coach, role=UserRelationship.ROLE_VIEWER, status=UserRelationship.STATUS_ACTIVE
        )
        coach_client = _bearer_client(coach)
        response = coach_client.post(
            "/v1/auth/tokens",
            {"name": "Should fail", "scopes": ["workouts:write"], "athlete_id": self.athlete.id},
            format="json",
        )
        self.assertEqual(response.status_code, 403)

    def test_create_virtual_coach_issues_a_usable_password_that_logs_in(self):
        created = self.client.post(
            "/v1/shares/virtual-coach", {"name": "Claude.ai", "scopes": ["activities:read"]}, format="json"
        ).json()

        response = APIClient().post(
            "/v1/auth/login", {"email": created["email"], "password": created["password"]}, format="json"
        )
        self.assertEqual(response.status_code, 200)

    def test_a_virtual_coachs_oauth2_session_delegates_to_its_athlete(self):
        created = self.client.post(
            "/v1/shares/virtual-coach", {"name": "Claude.ai", "scopes": ["activities:read"]}, format="json"
        ).json()
        coach = User.objects.get(email=created["email"])

        # A real OAuth2 session for the virtual coach - the same kind of session Claude.ai's
        # connector ends up with after completing an interactive login as it - not the delegated
        # PAT already covered above.
        oauth_client = _bearer_client(coach, scope="activities:read")
        listing = oauth_client.get("/v1/workouts")
        self.assertEqual(listing.status_code, 200)
        self.assertEqual(listing.json()["data"][0]["id"], self.workout.id)

    def test_a_real_coachs_own_oauth2_session_is_not_delegated_even_with_one_athlete(self):
        # The whole point of scoping this to is_virtual: a real user's own OAuth2 session (their
        # normal web app login) must never silently start showing someone else's data just
        # because they happen to coach exactly one athlete.
        coach = User.objects.create_user(email="real-coach-oauth-delegation@example.cc", password="x", name="Coach")
        UserRelationship.objects.create(
            owner=self.athlete, grantee=coach, role=UserRelationship.ROLE_COACH, status=UserRelationship.STATUS_ACTIVE
        )
        coach_client = _bearer_client(coach)
        listing = coach_client.get("/v1/workouts")
        self.assertEqual(listing.status_code, 200)
        self.assertEqual(listing.json()["data"], [])

    def test_revoking_the_share_deletes_the_whole_virtual_account(self):
        created = self.client.post(
            "/v1/shares/virtual-coach", {"name": "Claude.ai", "scopes": ["activities:read"]}, format="json"
        ).json()

        self.client.delete(f"/v1/shares/{created['share']['id']}")

        self.assertFalse(User.objects.filter(email=created["email"]).exists())

    def test_virtual_accounts_cannot_be_invited_as_an_ordinary_share(self):
        created = self.client.post(
            "/v1/shares/virtual-coach", {"name": "Claude.ai", "scopes": ["activities:read"]}, format="json"
        ).json()
        virtual_coach = User.objects.get(email=UserRelationship.objects.get(pk=created["share"]["id"]).grantee.email)
        other_athlete_client = _bearer_client(
            User.objects.create_user(email="other-athlete@example.cc", password="x", name="Other")
        )

        response = other_athlete_client.post(
            "/v1/shares", {"invitee": virtual_coach.email, "role": "coach"}, format="json"
        )
        self.assertEqual(response.status_code, 400)


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


class GrantAdminCommandTests(TestCase):
    def test_grants_admin_by_email(self):
        user = User.objects.create_user(email="future-admin@example.cc", password="x", name="Future Admin")
        self.assertFalse(user.is_admin)

        call_command("grant_admin", "--email", "future-admin@example.cc", stdout=StringIO())

        user.refresh_from_db()
        self.assertTrue(user.is_admin)

    def test_revoke_removes_admin(self):
        user = User.objects.create_user(email="ex-admin@example.cc", password="x", name="Ex Admin", is_admin=True)

        call_command("grant_admin", "--email", "ex-admin@example.cc", "--revoke", stdout=StringIO())

        user.refresh_from_db()
        self.assertFalse(user.is_admin)

    def test_email_match_is_case_insensitive(self):
        User.objects.create_user(email="mixed-case@example.cc", password="x", name="Mixed Case")
        call_command("grant_admin", "--email", "Mixed-Case@Example.cc", stdout=StringIO())
        self.assertTrue(User.objects.get(email="mixed-case@example.cc").is_admin)

    def test_unknown_email_raises_command_error(self):
        with self.assertRaises(CommandError):
            call_command("grant_admin", "--email", "nobody@example.cc", stdout=StringIO())


# Mirrors backend_java's EmailVerificationService/Controller test coverage - found missing
# entirely (along with the field and the whole feature) during a Django-vs-Java parity audit.
class EmailVerificationTests(TestCase):
    def setUp(self):
        self.user = User.objects.create_user(email="verify-me@example.cc", password="s3cret-pass", name="Athlete")

    def _issue_token(self) -> str:
        from accounts import email_verification

        with mock.patch("accounts.email_verification.send_verification_email") as mocked:
            email_verification.issue_and_send(self.user)
        link = mocked.call_args[0][1]
        return link.split("token=")[1]

    def test_verify_with_a_valid_token_marks_the_user_verified(self):
        raw_token = self._issue_token()

        response = APIClient().post("/v1/auth/verify-email", {"token": raw_token}, format="json")

        self.assertEqual(response.status_code, 204)
        self.user.refresh_from_db()
        self.assertTrue(self.user.email_verified)

    def test_verify_with_an_unknown_token_is_rejected(self):
        response = APIClient().post("/v1/auth/verify-email", {"token": "cad_evt_not-a-real-token"}, format="json")
        self.assertEqual(response.status_code, 400)

    def test_verify_with_an_already_used_token_is_rejected(self):
        raw_token = self._issue_token()
        first = APIClient().post("/v1/auth/verify-email", {"token": raw_token}, format="json")
        self.assertEqual(first.status_code, 204)

        second = APIClient().post("/v1/auth/verify-email", {"token": raw_token}, format="json")
        self.assertEqual(second.status_code, 400)

    def test_verify_with_an_expired_token_is_rejected(self):
        token = EmailVerificationToken.objects.create(
            user=self.user, hashed_secret="a" * 64, expires_at=timezone.now() - timedelta(hours=1)
        )
        response = APIClient().post("/v1/auth/verify-email", {"token": "irrelevant"}, format="json")
        # Wrong secret entirely also 400s - this asserts the row exists and is simply expired,
        # not that the lookup itself failed for an unrelated reason.
        self.assertFalse(token.is_usable(timezone.now()))
        self.assertEqual(response.status_code, 400)

    def test_resend_issues_a_fresh_token_and_emails_it(self):
        with mock.patch("accounts.email_verification.send_verification_email") as mocked:
            response = _bearer_client(self.user).post("/v1/auth/resend-verification")
        self.assertEqual(response.status_code, 204)
        mocked.assert_called_once()
        self.assertEqual(EmailVerificationToken.objects.filter(user=self.user).count(), 1)

    def test_resend_when_already_verified_conflicts(self):
        self.user.email_verified = True
        self.user.save(update_fields=["email_verified"])

        response = _bearer_client(self.user).post("/v1/auth/resend-verification")

        self.assertEqual(response.status_code, 409)

    def test_resend_within_the_cooldown_is_throttled(self):
        with mock.patch("accounts.email_verification.send_verification_email"):
            first = _bearer_client(self.user).post("/v1/auth/resend-verification")
        self.assertEqual(first.status_code, 204)

        second = _bearer_client(self.user).post("/v1/auth/resend-verification")

        self.assertEqual(second.status_code, 429)

    def test_resend_requires_authentication(self):
        response = APIClient().post("/v1/auth/resend-verification")
        self.assertEqual(response.status_code, 401)
