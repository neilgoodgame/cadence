from django.db import models

from accounts.models import User
from core.models import PrefixedIDModel

STATUS_CHOICES = [
    ("queued", "Queued"),
    ("processing", "Processing"),
    ("ready", "Ready"),
    ("failed", "Failed"),
]


class ExportJob(PrefixedIDModel):
    id_prefix = "exp"

    # One export on record per athlete - a new request replaces the previous job/file
    # outright (see views.ExportView.post), same as the Java backend's ExportJob.
    athlete = models.OneToOneField(User, on_delete=models.CASCADE, related_name="export_job")
    status = models.CharField(max_length=12, choices=STATUS_CHOICES, default="queued")
    stored_path = models.CharField(max_length=255, blank=True, default="")
    file_size_bytes = models.BigIntegerField(null=True, blank=True)
    error_message = models.CharField(max_length=500, blank=True, default="")
    created_at = models.DateTimeField(auto_now_add=True)
    completed_at = models.DateTimeField(null=True, blank=True)

    def __str__(self) -> str:
        return f"{self.athlete_id} export ({self.status})"


class ImportJob(PrefixedIDModel):
    id_prefix = "imp"

    # Kept as history, like Upload - not unique per athlete.
    athlete = models.ForeignKey(User, on_delete=models.CASCADE, related_name="import_jobs")
    status = models.CharField(max_length=12, choices=STATUS_CHOICES, default="queued")
    activities_imported = models.IntegerField(default=0)
    races_imported = models.IntegerField(default=0)
    workouts_imported = models.IntegerField(default=0)
    scheduled_workouts_imported = models.IntegerField(default=0)
    bikes_imported = models.IntegerField(default=0)
    shoes_imported = models.IntegerField(default=0)
    components_imported = models.IntegerField(default=0)
    items_skipped = models.IntegerField(default=0)
    error_message = models.CharField(max_length=500, blank=True, default="")
    created_at = models.DateTimeField(auto_now_add=True)
    completed_at = models.DateTimeField(null=True, blank=True)

    class Meta:
        ordering = ["-created_at"]

    def __str__(self) -> str:
        return f"{self.athlete_id} import ({self.status})"
