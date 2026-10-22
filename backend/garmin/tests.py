import base64
from datetime import UTC, datetime, timedelta

from django.test import SimpleTestCase, TestCase, override_settings
from rest_framework.test import APIClient

from accounts.models import User
from authn.oauth_utils import issue_token_pair

from . import crypto
from .models import GarminConnection

KEY_A = base64.b64encode(b"0123456789abcdef0123456789abcdef").decode()
KEY_B = base64.b64encode(b"fedcba9876543210fedcba9876543210").decode()
API_KEY = "garmin-connection-test-key-0001"
FAR_FUTURE = "2999-01-01"


def _bearer_client(user, scope="activities:read activities:write"):
    access_token, _ = issue_token_pair(user, scope=scope)
    client = APIClient()
    client.credentials(HTTP_AUTHORIZATION=f"Bearer {access_token.token}")
    return client


@override_settings(GARMIN_ENCRYPTION_KEY=KEY_A)
class GarminCryptoTests(SimpleTestCase):
    def test_round_trips_and_never_stores_plaintext(self):
        stored = crypto.encrypt("my-secret-garmin-key", "usr_1")
        self.assertTrue(stored.startswith("v1."))
        self.assertNotIn("my-secret-garmin-key", stored)
        self.assertEqual(crypto.decrypt(stored, "usr_1"), "my-secret-garmin-key")

    def test_uses_a_fresh_iv_each_time(self):
        self.assertNotEqual(crypto.encrypt("same", "usr_1"), crypto.encrypt("same", "usr_1"))

    def test_ciphertext_is_bound_to_its_owner(self):
        stored = crypto.encrypt("k-for-user-1", "usr_1")
        with self.assertRaises(RuntimeError):
            crypto.decrypt(stored, "usr_2")

    def test_a_different_key_cannot_decrypt(self):
        stored = crypto.encrypt("secret-value", "usr_1")
        with override_settings(GARMIN_ENCRYPTION_KEY=KEY_B), self.assertRaises(RuntimeError):
            crypto.decrypt(stored, "usr_1")

    def test_tampered_ciphertext_is_rejected(self):
        stored = crypto.encrypt("secret-value", "usr_1")
        # A character in the middle always carries data bits (the last one can be padding).
        mid = len(stored) // 2
        tampered = stored[:mid] + ("B" if stored[mid] == "A" else "A") + stored[mid + 1 :]
        with self.assertRaises(RuntimeError):
            crypto.decrypt(tampered, "usr_1")

    def test_fails_closed_when_no_key_is_configured(self):
        with override_settings(GARMIN_ENCRYPTION_KEY=""), self.assertRaises(crypto.GarminNotConfiguredError):
            crypto.encrypt("x", "usr_1")

    def test_rejects_a_key_of_the_wrong_length(self):
        with (
            override_settings(GARMIN_ENCRYPTION_KEY=base64.b64encode(b"too-short").decode()),
            self.assertRaises(RuntimeError),
        ):
            crypto.encrypt("x", "usr_1")

    def test_decrypts_a_fixture_shared_with_the_java_backend(self):
        # The same literal is pinned in backend_java's GarminKeyCipherTest, so either backend
        # can read a row the other wrote.
        self.assertEqual(
            crypto.decrypt("v1.PIvZpTfrpcQb7tiDRkEIMoym01OQ5qPWPDmXLxmzC-q2h5PNzd-eDclZBq8", "usr_parity"),
            "parity-check-key",
        )


@override_settings(GARMIN_ENCRYPTION_KEY=KEY_A)
class GarminConnectionViewTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(email="garmin-conn@example.cc", password="x", name="Athlete")
        self.other = User.objects.create_user(email="garmin-conn-other@example.cc", password="x", name="Other")
        self.client_ = _bearer_client(self.athlete)

    def _put(self, client=None, **body):
        return (client or self.client_).put("/v1/garmin/connection", body, format="json")

    def test_saves_an_encrypted_key_defaults_the_email_and_never_returns_the_key(self):
        response = self._put(api_key=API_KEY, key_expires_on=FAR_FUTURE)
        self.assertEqual(response.status_code, 200)
        body = response.json()
        self.assertTrue(body["connected"])
        self.assertEqual(body["email"], "garmin-conn@example.cc")
        self.assertEqual(body["key_expires_on"], FAR_FUTURE)
        self.assertNotIn(API_KEY, response.content.decode())

        stored = GarminConnection.objects.get(owner=self.athlete)
        self.assertNotIn(API_KEY, stored.api_key_encrypted)
        self.assertEqual(crypto.decrypt(stored.api_key_encrypted, self.athlete.id), API_KEY)

    def test_a_different_garmin_email_is_kept_and_editing_without_a_key_leaves_the_key_alone(self):
        self._put(api_key=API_KEY, email="garmin-account@example.cc")
        response = self._put(email="other-garmin@example.cc")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["email"], "other-garmin@example.cc")

        stored = GarminConnection.objects.get(owner=self.athlete)
        self.assertEqual(crypto.decrypt(stored.api_key_encrypted, self.athlete.id), API_KEY)

    def test_first_save_without_a_key_is_rejected(self):
        self.assertEqual(self._put().status_code, 400)

    def test_a_past_expiry_date_is_rejected(self):
        past = (datetime.now(UTC).date() - timedelta(days=1)).isoformat()
        self.assertEqual(self._put(api_key=API_KEY, key_expires_on=past).status_code, 400)

    def test_a_too_short_key_is_rejected(self):
        self.assertEqual(self._put(api_key="short").status_code, 400)

    @override_settings(GARMIN_ENCRYPTION_KEY="")
    def test_returns_503_when_key_storage_is_not_configured(self):
        self.assertEqual(self._put(api_key=API_KEY).status_code, 503)

    def test_delete_removes_the_connection_and_others_cannot_see_it(self):
        self._put(api_key=API_KEY)
        seen_by_other = _bearer_client(self.other).get("/v1/garmin/connection")
        self.assertFalse(seen_by_other.json()["connected"])

        self.assertEqual(self.client_.delete("/v1/garmin/connection").status_code, 204)
        self.assertFalse(self.client_.get("/v1/garmin/connection").json()["connected"])
        self.assertFalse(GarminConnection.objects.filter(owner=self.athlete).exists())
