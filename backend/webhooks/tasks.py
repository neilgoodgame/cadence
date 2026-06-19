import hashlib
import hmac
import json

import requests
from celery import shared_task

from .models import WebhookDelivery

DELIVERY_TIMEOUT_SECONDS = 5
MAX_ATTEMPTS = 6


def sign_payload(secret, raw_body):
    return hmac.new(secret.encode("utf-8"), raw_body, hashlib.sha256).hexdigest()


@shared_task(
    bind=True,
    autoretry_for=(requests.RequestException,),
    retry_backoff=True,
    retry_backoff_max=600,
    max_retries=MAX_ATTEMPTS - 1,
)
def deliver_webhook(self, delivery_id):
    delivery = WebhookDelivery.objects.select_related("webhook").get(pk=delivery_id)
    webhook = delivery.webhook
    raw_body = json.dumps(delivery.payload).encode("utf-8")

    delivery.attempts += 1
    try:
        response = requests.post(
            webhook.url,
            data=raw_body,
            headers={
                "Content-Type": "application/json",
                "X-Cadence-Signature": sign_payload(webhook.secret, raw_body),
                "X-Cadence-Event": delivery.event,
                "X-Cadence-Delivery": str(delivery.id),
            },
            timeout=DELIVERY_TIMEOUT_SECONDS,
        )
        response.raise_for_status()
    except requests.RequestException as exc:
        delivery.last_error = str(exc)
        delivery.status = "failed" if self.request.retries >= self.max_retries else "pending"
        delivery.save(update_fields=["attempts", "status", "last_error"])
        raise
    else:
        delivery.status = "succeeded"
        delivery.last_error = ""
        delivery.save(update_fields=["attempts", "status", "last_error"])
