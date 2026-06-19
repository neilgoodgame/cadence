from django.db import models

from accounts.models import User
from core.models import PrefixedIDModel


class Workout(PrefixedIDModel):
    id_prefix = "wkt"

    SPORT_CHOICES = [
        ("bike", "Bike"),
        ("run", "Run"),
    ]

    # Internal-only — not part of the public Workout schema in openapi.yaml.
    # Used purely for list-scoping ("the athlete's workout library") and to
    # resolve the target athlete for permission checks on detail/update/delete.
    # Never serialize this field.
    created_by = models.ForeignKey(User, on_delete=models.CASCADE, related_name="workouts")

    name = models.CharField(max_length=200)
    sport = models.CharField(max_length=10, choices=SPORT_CHOICES)
    # Free-text classification (e.g. "vo2"). Not settable via any documented
    # request body — clients can never write it, so it just stays blank.
    type = models.CharField(max_length=50, blank=True, default="")
    duration = models.IntegerField(default=0)
    tss = models.IntegerField(default=0)

    def __str__(self):
        return self.name


class WorkoutStep(models.Model):
    """Not fetched by its own id, so it uses a plain BigAutoField per the
    core.models.PrefixedIDModel convention. `order` makes the ordered `steps`
    array on the workout detail response reconstructable.
    """

    KIND_CHOICES = [
        ("warmup", "Warmup"),
        ("block", "Block"),
        ("rec", "Recovery"),
        ("cool", "Cooldown"),
    ]
    END_TYPE_CHOICES = [
        ("time", "Time"),
        ("distance", "Distance"),
        ("manual", "Manual"),
    ]

    workout = models.ForeignKey(Workout, on_delete=models.CASCADE, related_name="steps")
    order = models.IntegerField(default=0)
    kind = models.CharField(max_length=10, choices=KIND_CHOICES)
    end_type = models.CharField(max_length=10, choices=END_TYPE_CHOICES)
    duration = models.IntegerField(null=True, blank=True)
    distance = models.IntegerField(null=True, blank=True)
    target_pct = models.FloatField(null=True, blank=True)
    repeat = models.IntegerField(default=1)

    class Meta:
        ordering = ["order"]

    def __str__(self):
        return f"{self.workout_id} step {self.order} ({self.kind})"
