from django.db import models

from accounts.models import User

ZONE_TYPE_CHOICES = [
    ("heart_rate", "Heart rate"),
    ("bike_power", "Bike power"),
    ("run_power", "Run power"),
    ("pace", "Pace"),
]


class ZoneSet(models.Model):
    """A set of training zone boundaries for one metric, expressed as percentages
    of a threshold value. Addressed via (athlete, type), never fetched by its own
    id, so it uses a plain BigAutoField per the core.models.PrefixedIDModel convention.
    """

    athlete = models.ForeignKey(User, on_delete=models.CASCADE, related_name="zone_sets")
    type = models.CharField(max_length=20, choices=ZONE_TYPE_CHOICES)
    zones = models.JSONField(default=list)

    class Meta:
        constraints = [
            models.UniqueConstraint(fields=["athlete", "type"], name="unique_athlete_zone_type"),
        ]

    def __str__(self) -> str:
        return f"{self.athlete_id} {self.type}"
