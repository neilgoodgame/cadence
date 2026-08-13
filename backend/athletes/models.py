from django.db import models

from accounts.models import User
from activities.models import Activity

ZONE_TYPE_CHOICES = [
    ("heart_rate", "Heart rate"),
    ("bike_power", "Bike power"),
    ("run_power", "Run power"),
    ("pace", "Pace"),
]

THRESHOLD_FIELD_CHOICES = [
    ("ftp", "FTP"),
    ("critical_run_power", "Critical running power"),
    ("threshold_pace", "Threshold pace"),
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


class ThresholdHistory(models.Model):
    """One row per (athlete, field) every time the rolling-window-derived threshold value
    actually changes - see athletes/threshold_history.py. The sole source of truth for "what was
    this athlete's threshold at any given point in time": an activity's own effective threshold
    is a lookup here (the most recent entry with effective_from <= that activity's start_date),
    not a value duplicated onto every Activity row. Not fetched by its own id, so it uses a plain
    BigAutoField per the core.models.PrefixedIDModel convention (same as ZoneSet/BestEffort).
    """

    athlete = models.ForeignKey(User, on_delete=models.CASCADE, related_name="threshold_history")
    field = models.CharField(max_length=20, choices=THRESHOLD_FIELD_CHOICES)
    # Dual-typed like the fields this replaces (Activity.ftp_snapshot vs threshold_pace_snapshot,
    # User.ftp vs threshold_pace): value_numeric for ftp/critical_run_power, value_pace ("M:SS")
    # for threshold_pace - only one is ever populated, matching which `field` this row is.
    value_numeric = models.PositiveIntegerField(null=True, blank=True)
    value_pace = models.CharField(max_length=10, blank=True, default="")
    source_activity = models.ForeignKey(Activity, on_delete=models.CASCADE, related_name="threshold_history")
    effective_from = models.DateField()
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        indexes = [
            models.Index(fields=["athlete", "field", "effective_from"]),
        ]
        verbose_name_plural = "threshold history"

    def __str__(self) -> str:
        value = self.value_numeric if self.value_numeric is not None else self.value_pace
        return f"{self.athlete_id} {self.field}={value} as of {self.effective_from}"
