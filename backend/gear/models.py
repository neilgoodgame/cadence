from django.db import models

from accounts.models import User
from core.models import PrefixedIDModel


class Bike(PrefixedIDModel):
    id_prefix = "bike"

    KIND_CHOICES = [
        ("road", "Road"),
        ("indoor", "Indoor"),
        ("gravel", "Gravel"),
        ("tt", "Time trial"),
    ]

    athlete = models.ForeignKey(User, on_delete=models.CASCADE, related_name="bikes")
    name = models.CharField(max_length=150)
    kind = models.CharField(max_length=10, choices=KIND_CHOICES, blank=True, default="")
    groupset = models.CharField(max_length=150, blank=True, default="")
    distance_km = models.IntegerField(default=0)
    # Not settable via any documented request body yet — populated by a later
    # upload-pipeline phase. Default to 0 for now.
    hours = models.FloatField(default=0)
    rides = models.IntegerField(default=0)

    def __str__(self) -> str:
        return self.name


class Component(PrefixedIDModel):
    id_prefix = "cmp"

    bike = models.ForeignKey(Bike, on_delete=models.CASCADE, related_name="components")
    name = models.CharField(max_length=150)
    km = models.IntegerField(default=0)
    limit_km = models.IntegerField()
    model = models.CharField(max_length=150, blank=True, default="")

    def __str__(self) -> str:
        return f"{self.name} ({self.bike_id})"


class ServiceRecord(PrefixedIDModel):
    id_prefix = "svc"

    ACTION_CHOICES = [
        ("replaced", "Replaced"),
        ("cleaned", "Cleaned"),
        ("inspected", "Inspected"),
        ("adjusted", "Adjusted"),
    ]

    component = models.ForeignKey(Component, on_delete=models.CASCADE, related_name="service_records")
    action = models.CharField(max_length=10, choices=ACTION_CHOICES, blank=True, default="")
    reset = models.BooleanField(default=True)
    note = models.CharField(max_length=500, blank=True, default="")
    date = models.DateField()

    def __str__(self) -> str:
        return f"{self.component_id} {self.action} {self.date}"


class ShoeModel(PrefixedIDModel):
    """Catalog entry — a manufacturer + product line. Not athlete-owned."""

    id_prefix = "sm"

    manufacturer = models.CharField(max_length=150)
    model = models.CharField(max_length=150)
    created_by = models.ForeignKey(
        User,
        null=True,
        blank=True,
        on_delete=models.SET_NULL,
        related_name="created_shoe_models",
    )

    def __str__(self) -> str:
        return f"{self.manufacturer} {self.model}"


class ShoeModelVersion(PrefixedIDModel):
    """Catalog entry — a generation of a shoe model. Not athlete-owned."""

    id_prefix = "smv"

    shoe_model = models.ForeignKey(ShoeModel, on_delete=models.CASCADE, related_name="versions")
    version = models.CharField(max_length=50, blank=True, default="")

    def __str__(self) -> str:
        return f"{self.shoe_model} {self.version}"


class Shoe(PrefixedIDModel):
    id_prefix = "shoe"

    athlete = models.ForeignKey(User, on_delete=models.CASCADE, related_name="shoes")
    shoe_model_version = models.ForeignKey(ShoeModelVersion, on_delete=models.PROTECT, related_name="shoes")
    colourway = models.CharField(max_length=150, blank=True, default="")
    name = models.CharField(max_length=200)
    image = models.URLField(null=True, blank=True)  # noqa: DJ001 -- API contract treats absent image as null, not ""
    role = models.CharField(max_length=150, blank=True, default="")
    km = models.IntegerField(default=0)
    limit_km = models.IntegerField(default=0)
    since = models.DateField(auto_now_add=True)
    # Soft-archive flag — accepted on write but excluded from the response schema.
    retired = models.BooleanField(default=False)

    class Meta:
        constraints = [
            models.UniqueConstraint(fields=["athlete", "name"], name="unique_athlete_shoe_name"),
        ]

    def __str__(self) -> str:
        return self.name
