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
    OVERLAP_HANDLING_CHOICES = [
        ("off", "Off"),
        ("flag", "Flag"),
        ("mark", "Mark"),
        ("merge", "Merge"),
    ]

    athlete = models.ForeignKey(User, on_delete=models.CASCADE, related_name="upload_batches")
    filename = models.CharField(max_length=255)
    on_duplicate = models.CharField(max_length=10, choices=ON_DUPLICATE_CHOICES, default="skip")
    # Resolved once at batch creation (the request's own on_overlap, else the athlete's
    # overlap_handling default) - see services.create_batch_from_files. Never null past that.
    on_overlap = models.CharField(max_length=10, choices=OVERLAP_HANDLING_CHOICES, default="off")
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
        ("skipped", "Skipped"),
    ]
    OVERLAP_ACTION_CHOICES = [
        ("flagged", "Flagged"),
        ("marked", "Marked"),
        ("merged", "Merged"),
        ("merge_skipped_low_score", "Merge skipped (low score)"),
    ]

    athlete = models.ForeignKey(User, on_delete=models.CASCADE, related_name="uploads")
    batch = models.ForeignKey(UploadBatch, null=True, blank=True, on_delete=models.CASCADE, related_name="uploads")
    filename = models.CharField(max_length=255)
    file_hash = models.CharField(max_length=64, db_index=True)
    stored_path = models.CharField(max_length=255, blank=True, default="")
    status = models.CharField(max_length=12, choices=STATUS_CHOICES, default="queued")
    progress = models.FloatField(default=0.0)
    activity = models.ForeignKey(Activity, null=True, blank=True, on_delete=models.SET_NULL, related_name="uploads")
    # Issue #498: set when on-ingest overlap detection found this upload's activity overlapping
    # an existing one and on_overlap wasn't off. Null whenever detection didn't run or found
    # nothing.
    overlap_other_activity = models.ForeignKey(
        Activity, null=True, blank=True, on_delete=models.SET_NULL, related_name="+"
    )
    overlap_action = models.CharField(max_length=25, choices=OVERLAP_ACTION_CHOICES, blank=True, default="")
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
