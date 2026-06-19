from django.db import models

from accounts.models import User
from core.models import PrefixedIDModel


class Webhook(PrefixedIDModel):
    id_prefix = "whk"

    STATUS_CHOICES = [
        ("active", "Active"),
        ("disabled", "Disabled"),
    ]
    EVENT_CHOICES = ["activity.created", "scheduled_workout.matched", "upload_batch.completed"]

    owner = models.ForeignKey(User, on_delete=models.CASCADE, related_name="webhooks")
    url = models.URLField()
    status = models.CharField(max_length=10, choices=STATUS_CHOICES, default="active")
    events = models.JSONField(default=list)
    # Used to HMAC-sign every delivery, not just verified once like a password —
    # stored as plaintext (never hashed) so the worker can re-sign on each retry.
    secret = models.CharField(max_length=64)
    created = models.DateTimeField(auto_now_add=True)

    def __str__(self):
        return f"{self.url} ({self.status})"


class WebhookDelivery(models.Model):
    """Internal delivery-attempt bookkeeping — not part of the public API surface."""

    STATUS_CHOICES = [
        ("pending", "Pending"),
        ("succeeded", "Succeeded"),
        ("failed", "Failed"),
    ]

    webhook = models.ForeignKey(Webhook, on_delete=models.CASCADE, related_name="deliveries")
    event = models.CharField(max_length=50)
    payload = models.JSONField()
    status = models.CharField(max_length=10, choices=STATUS_CHOICES, default="pending")
    attempts = models.IntegerField(default=0)
    last_error = models.TextField(blank=True, default="")
    created = models.DateTimeField(auto_now_add=True)

    def __str__(self):
        return f"{self.webhook_id} {self.event} ({self.status})"
