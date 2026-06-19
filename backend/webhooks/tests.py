import hashlib
import hmac
from unittest.mock import MagicMock, patch

import requests
from django.test import TestCase, override_settings
from rest_framework.test import APIClient

from accounts.models import User, UserRelationship
from authn.oauth_utils import issue_token_pair

from .events import fire_event
from .models import Webhook, WebhookDelivery
from .tasks import MAX_ATTEMPTS, deliver_webhook


def _bearer_client(user, scope="activities:read activities:write"):
    access_token, _ = issue_token_pair(user, scope=scope)
    client = APIClient()
    client.credentials(HTTP_AUTHORIZATION=f"Bearer {access_token.token}")
    return client


class WebhookCRUDViewTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(email="athlete@example.cc", password="x", name="Athlete")
        self.other = User.objects.create_user(email="other@example.cc", password="x", name="Other")

    def test_create_returns_secret_once(self):
        response = _bearer_client(self.athlete).post(
            "/v1/webhooks",
            {"url": "https://example.cc/hook", "events": ["activity.created"]},
            format="json",
        )
        self.assertEqual(response.status_code, 201)
        body = response.json()
        self.assertTrue(body["secret"].startswith("whsec_"))
        self.assertEqual(body["status"], "active")
        self.assertEqual(body["events"], ["activity.created"])

    def test_create_rejects_unknown_event(self):
        response = _bearer_client(self.athlete).post(
            "/v1/webhooks", {"url": "https://example.cc/hook", "events": ["bogus.event"]}, format="json"
        )
        self.assertEqual(response.status_code, 400)

    def test_create_requires_at_least_one_event(self):
        response = _bearer_client(self.athlete).post(
            "/v1/webhooks", {"url": "https://example.cc/hook", "events": []}, format="json"
        )
        self.assertEqual(response.status_code, 400)

    def test_list_excludes_secret_and_scopes_to_owner(self):
        Webhook.objects.create(
            owner=self.athlete, url="https://example.cc/a", events=["activity.created"], secret="whsec_mine"
        )
        Webhook.objects.create(
            owner=self.other, url="https://example.cc/b", events=["activity.created"], secret="whsec_notmine"
        )

        response = _bearer_client(self.athlete).get("/v1/webhooks")
        self.assertEqual(response.status_code, 200)
        data = response.json()["data"]
        self.assertEqual(len(data), 1)
        self.assertEqual(data[0]["url"], "https://example.cc/a")
        self.assertNotIn("secret", data[0])

    def test_delete_removes_webhook(self):
        webhook = Webhook.objects.create(
            owner=self.athlete, url="https://example.cc/a", events=["activity.created"], secret="whsec_mine"
        )
        response = _bearer_client(self.athlete).delete(f"/v1/webhooks/{webhook.id}")
        self.assertEqual(response.status_code, 204)
        self.assertFalse(Webhook.objects.filter(pk=webhook.id).exists())

    def test_cannot_delete_someone_elses_webhook(self):
        webhook = Webhook.objects.create(
            owner=self.other, url="https://example.cc/a", events=["activity.created"], secret="whsec_notmine"
        )
        response = _bearer_client(self.athlete).delete(f"/v1/webhooks/{webhook.id}")
        self.assertEqual(response.status_code, 404)
        self.assertTrue(Webhook.objects.filter(pk=webhook.id).exists())


class WebhookDeliveryTaskTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(email="athlete@example.cc", password="x", name="Athlete")
        self.webhook = Webhook.objects.create(
            owner=self.athlete,
            url="https://example.cc/hook",
            events=["activity.created"],
            secret="whsec_test123",
        )

    def _make_delivery(self):
        return WebhookDelivery.objects.create(
            webhook=self.webhook,
            event="activity.created",
            payload={"event": "activity.created", "created": "2026-01-01T00:00:00Z", "data": {"id": "act_1"}},
        )

    def test_successful_delivery_marks_succeeded_and_signs_payload(self):
        delivery = self._make_delivery()
        mock_response = MagicMock(status_code=200)
        mock_response.raise_for_status.return_value = None

        with patch("webhooks.tasks.requests.post", return_value=mock_response) as mock_post:
            deliver_webhook.delay(delivery.id)

        delivery.refresh_from_db()
        self.assertEqual(delivery.status, "succeeded")
        self.assertEqual(delivery.attempts, 1)

        _, kwargs = mock_post.call_args
        self.assertEqual(kwargs["headers"]["X-Cadence-Event"], "activity.created")
        self.assertEqual(kwargs["headers"]["X-Cadence-Delivery"], str(delivery.id))
        expected_signature = hmac.new(b"whsec_test123", kwargs["data"], hashlib.sha256).hexdigest()
        self.assertEqual(kwargs["headers"]["X-Cadence-Signature"], expected_signature)

    # CELERY_TASK_EAGER_PROPAGATES (on in settings_test.py) makes eager mode
    # re-raise the internal Retry signal immediately instead of looping, so
    # multi-attempt retries need it off here; .get() still surfaces the
    # final outcome/exception once the simulated attempts are exhausted.
    @override_settings(CELERY_TASK_EAGER_PROPAGATES=False)
    def test_recovers_after_transient_failures_then_succeeds(self):
        delivery = self._make_delivery()
        mock_response = MagicMock(status_code=200)
        mock_response.raise_for_status.return_value = None

        with patch(
            "webhooks.tasks.requests.post",
            side_effect=[requests.ConnectionError("boom"), requests.ConnectionError("boom"), mock_response],
        ):
            deliver_webhook.delay(delivery.id).get()

        delivery.refresh_from_db()
        self.assertEqual(delivery.status, "succeeded")
        self.assertEqual(delivery.attempts, 3)

    @override_settings(CELERY_TASK_EAGER_PROPAGATES=False)
    def test_permanently_fails_after_max_attempts(self):
        delivery = self._make_delivery()

        with patch("webhooks.tasks.requests.post", side_effect=requests.ConnectionError("boom")):
            with self.assertRaises(Exception):
                deliver_webhook.delay(delivery.id).get()

        delivery.refresh_from_db()
        self.assertEqual(delivery.attempts, MAX_ATTEMPTS)
        self.assertEqual(delivery.status, "failed")
        self.assertIn("boom", delivery.last_error)


class FireEventTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(email="athlete@example.cc", password="x", name="Athlete")
        self.coach = User.objects.create_user(email="coach@example.cc", password="x", name="Coach")

    @patch("webhooks.events.deliver_webhook.delay")
    def test_dispatches_only_to_subscribed_event(self, mock_delay):
        Webhook.objects.create(
            owner=self.athlete, url="https://example.cc/a", events=["activity.created"], secret="s1"
        )
        Webhook.objects.create(
            owner=self.athlete, url="https://example.cc/b", events=["upload_batch.completed"], secret="s2"
        )

        fire_event("activity.created", self.athlete.id, {"id": "act_1"})

        self.assertEqual(mock_delay.call_count, 1)
        self.assertEqual(WebhookDelivery.objects.count(), 1)
        self.assertEqual(WebhookDelivery.objects.first().event, "activity.created")

    @patch("webhooks.events.deliver_webhook.delay")
    def test_skips_disabled_webhooks(self, mock_delay):
        Webhook.objects.create(
            owner=self.athlete,
            url="https://example.cc/a",
            events=["activity.created"],
            status="disabled",
            secret="s1",
        )
        fire_event("activity.created", self.athlete.id, {"id": "act_1"})
        mock_delay.assert_not_called()

    @patch("webhooks.events.deliver_webhook.delay")
    def test_respects_athlete_visibility(self, mock_delay):
        Webhook.objects.create(
            owner=self.coach, url="https://example.cc/a", events=["activity.created"], secret="s1"
        )

        fire_event("activity.created", self.athlete.id, {"id": "act_1"})
        mock_delay.assert_not_called()

        UserRelationship.objects.create(
            owner=self.athlete,
            grantee=self.coach,
            role=UserRelationship.ROLE_COACH,
            status=UserRelationship.STATUS_ACTIVE,
        )
        fire_event("activity.created", self.athlete.id, {"id": "act_1"})
        mock_delay.assert_called_once()

    @patch("webhooks.events.deliver_webhook.delay")
    def test_envelope_shape(self, mock_delay):
        Webhook.objects.create(
            owner=self.athlete, url="https://example.cc/a", events=["activity.created"], secret="s1"
        )
        fire_event("activity.created", self.athlete.id, {"id": "act_1"})

        delivery = WebhookDelivery.objects.get()
        self.assertEqual(set(delivery.payload), {"event", "created", "data"})
        self.assertEqual(delivery.payload["event"], "activity.created")
        self.assertEqual(delivery.payload["data"], {"id": "act_1"})
