from django.db import models

from accounts.models import User
from activities.models import Activity
from core.models import PrefixedIDModel
from gear.models import Shoe


class UploadBatch(PrefixedIDModel):
    id_prefix = "bat"

    STATUS_CHOICES = [
        ("unpacking", "Unpacking"),
        ("processing", "Processing"),
        ("completed", "Completed"),
        ("failed", "Failed"),
    ]
    ON_DUPLICATE_CHOICES = [
        ("skip", "Skip"),
        ("replace", "Replace"),
    ]

    athlete = models.ForeignKey(User, on_delete=models.CASCADE, related_name="upload_batches")
    filename = models.CharField(max_length=255)
    on_duplicate = models.CharField(max_length=10, choices=ON_DUPLICATE_CHOICES, default="skip")
    status = models.CharField(max_length=12, choices=STATUS_CHOICES, default="unpacking")
    received_at = models.DateTimeField(auto_now_add=True)
    completed_at = models.DateTimeField(null=True, blank=True)
    error_code = models.CharField(max_length=100, blank=True, default="")
    error_message = models.CharField(max_length=500, blank=True, default="")

    class Meta:
        ordering = ["-received_at"]

    def __str__(self) -> str:
        return self.filename


class Upload(PrefixedIDModel):
    id_prefix = "upl"

    STATUS_CHOICES = [
        ("queued", "Queued"),
        ("processing", "Processing"),
        ("ready", "Ready"),
        ("failed", "Failed"),
        ("duplicate", "Duplicate"),
    ]

    athlete = models.ForeignKey(User, on_delete=models.CASCADE, related_name="uploads")
    batch = models.ForeignKey(UploadBatch, null=True, blank=True, on_delete=models.CASCADE, related_name="uploads")
    filename = models.CharField(max_length=255)
    file_hash = models.CharField(max_length=64, db_index=True)
    stored_path = models.CharField(max_length=255, blank=True, default="")
    status = models.CharField(max_length=12, choices=STATUS_CHOICES, default="queued")
    progress = models.FloatField(default=0.0)
    activity = models.ForeignKey(Activity, null=True, blank=True, on_delete=models.SET_NULL, related_name="uploads")
    error_code = models.CharField(max_length=100, blank=True, default="")
    error_message = models.CharField(max_length=500, blank=True, default="")
    weight_before_kg = models.FloatField(null=True, blank=True)
    weight_after_kg = models.FloatField(null=True, blank=True)
    fluids_ml = models.IntegerField(null=True, blank=True)
    shoe = models.ForeignKey(Shoe, null=True, blank=True, on_delete=models.SET_NULL, related_name="uploads")
    received_at = models.DateTimeField(auto_now_add=True)
    completed_at = models.DateTimeField(null=True, blank=True)

    class Meta:
        ordering = ["-received_at"]

    def __str__(self) -> str:
        return self.filename
