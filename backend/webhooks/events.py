import logging
from typing import Any

from django.utils import timezone

from core.permissions import user_may_read

from .models import Webhook, WebhookDelivery
from .tasks import deliver_webhook

logger = logging.getLogger(__name__)


def fire_event(event: str, athlete_id: str, data: Any) -> None:
    """Enqueues a delivery for every active subscription that wants `event` and
    can see `athlete_id`'s data. Never lets a delivery problem bubble up to the
    caller — under CELERY_TASK_ALWAYS_EAGER (tests, or a broker hiccup in prod)
    `.delay()` can raise synchronously, and a bad webhook endpoint must not be
    able to fail an upload or scheduling action.
    """
    webhooks = Webhook.objects.filter(status="active", events__contains=[event])
    for webhook in webhooks:
        if not user_may_read(webhook.owner_id, athlete_id):
            continue
        delivery = WebhookDelivery.objects.create(
            webhook=webhook,
            event=event,
            payload={"event": event, "created": timezone.now().isoformat(), "data": data},
        )
        try:
            deliver_webhook.delay(delivery.id)
        except Exception:
            logger.exception("Failed to enqueue webhook delivery %s", delivery.id)
