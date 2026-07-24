from django.db import models

from accounts.models import User
from activities.models import Activity
from core.models import PrefixedIDModel

SPORT_CHOICES = [
    ("bike", "Bike"),
    ("run", "Run"),
    ("swim", "Swim"),
    ("multisport", "Multisport"),
]


class Race(PrefixedIDModel):
    id_prefix = "race"

    athlete = models.ForeignKey(User, on_delete=models.CASCADE, related_name="races")
    name = models.CharField(max_length=200)
    date = models.DateField()
    sport = models.CharField(max_length=10, choices=SPORT_CHOICES, blank=True, default="")
    distance_km = models.FloatField(null=True, blank=True)
    goal_time = models.DurationField(null=True, blank=True)
    result_time = models.DurationField(null=True, blank=True)
    activity = models.OneToOneField(
        Activity,
        on_delete=models.SET_NULL,
        null=True,
        blank=True,
        related_name="race_result",
    )
    url = models.URLField(null=True, blank=True)  # noqa: DJ001 -- API contract uses null, not ""
    results_url = models.URLField(null=True, blank=True)  # noqa: DJ001 -- link to athlete's result page
    notes = models.CharField(max_length=500, blank=True, default="")

    class Meta:
        ordering = ["date"]

    def __str__(self) -> str:
        return f"{self.name} ({self.date})"
