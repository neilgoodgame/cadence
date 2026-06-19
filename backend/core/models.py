import secrets

from django.db import models

_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"


def generate_id(prefix: str, length: int = 14) -> str:
    suffix = "".join(secrets.choice(_ALPHABET) for _ in range(length))
    return f"{prefix}_{suffix}"


class PrefixedIDModel(models.Model):
    """Abstract base for resources the API addresses by a Stripe-style string id.

    Entities that are never fetched by id directly (Record, Lap, BestEffort,
    DurationCurve, WorkoutStep, Zone) use a plain BigAutoField instead.
    """

    id_prefix = None

    id = models.CharField(primary_key=True, max_length=40, editable=False)

    class Meta:
        abstract = True

    def save(self, *args, **kwargs):
        if not self.id:
            self.id = generate_id(self.id_prefix)
        super().save(*args, **kwargs)
