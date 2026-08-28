from django.db import models

from accounts.models import User
from activities.models import Activity, BestEffort
from core.models import PrefixedIDModel

# Same shape as dataexport.models.STATUS_CHOICES - duplicated rather than imported since
# this is a different domain (best-effort recompute, not the export/import file transfer
# job family) and it's only 4 tuples.
STATUS_CHOICES = [
    ("queued", "Queued"),
    ("processing", "Processing"),
    ("ready", "Ready"),
    ("failed", "Failed"),
]

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
    is a lookup here (the most recent entry with current_from <= that activity's start_date),
    not a value duplicated onto every Activity row. Not fetched by its own id, so it uses a plain
    BigAutoField per the core.models.PrefixedIDModel convention (same as ZoneSet/BestEffort).
    """

    athlete = models.ForeignKey(User, on_delete=models.CASCADE, related_name="threshold_history")
    field = models.CharField(max_length=20, choices=THRESHOLD_FIELD_CHOICES)
    # Dual-typed like User.ftp vs threshold_pace: value_numeric for ftp/critical_run_power,
    # value_pace ("M:SS") for threshold_pace - only one is ever populated, matching `field`.
    value_numeric = models.PositiveIntegerField(null=True, blank=True)
    value_pace = models.CharField(max_length=10, blank=True, default="")
    # Null for a manually-entered value (see threshold_history.py::record_manual_value) - the
    # athlete declared it directly via their profile, not from a specific activity's effort.
    source_activity = models.ForeignKey(
        Activity, on_delete=models.CASCADE, related_name="threshold_history", null=True, blank=True
    )
    # The qualifying activity's own date - what "this activity set/previously defined your X"
    # display is keyed on. NOT necessarily the date this row started being the recorded current
    # value - see current_from below for that. Equal to current_from for the common case (this
    # candidate wins immediately, on its own date), but can be much earlier than current_from
    # when this row only became current later, via an *earlier* better entry aging out of the
    # window (the "not a one-way ratchet" case - see current_window_value's docstring). A row
    # dated e.g. 2023-09-03 that only overtook a better 2023-08-26 entry once that one aged out
    # 112 days later is a real, confirmed example - not a hypothetical.
    effective_from = models.DateField()
    # The date this row actually became the recorded current value - the date of whichever
    # activity's ingest/recompute pass first found this to be the new window winner (for a
    # cascading-expiry win, that's a *different*, later activity than effective_from's own one;
    # for the common immediate-win case, the two are equal). This is what an activity-scoped
    # lookup (athletes/zones.py::reference_for) must filter on, not effective_from - filtering on
    # effective_from lets a not-yet-current row (like the 2023-09-03 example above) match its own
    # activity's date, since effective_from <= that same date trivially holds, even though the
    # row wasn't actually in effect until 3+ months later.
    current_from = models.DateField()
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        indexes = [
            models.Index(fields=["athlete", "field", "effective_from"]),
            models.Index(fields=["athlete", "field", "current_from"]),
        ]
        verbose_name_plural = "threshold history"

    def __str__(self) -> str:
        value = self.value_numeric if self.value_numeric is not None else self.value_pace
        return f"{self.athlete_id} {self.field}={value} as of {self.effective_from}"


class BestEffortRecomputeJob(PrefixedIDModel):
    """Tracks an in-progress "recompute best efforts" run - see athletes/tasks.py's
    run_best_effort_recompute. Runs via Celery rather than synchronously (the original
    implementation was a StreamingHttpResponse generator) because a full account
    (thousands of activities) can take longer than gunicorn's sync-worker timeout; the
    frontend polls this row for progress instead, same pattern as dataexport's
    ExportJob/ImportJob.
    """

    id_prefix = "ber"

    # Kept as history, like ImportJob - not unique per athlete (recompute can be re-run,
    # e.g. after new activities land).
    athlete = models.ForeignKey(User, on_delete=models.CASCADE, related_name="best_effort_recompute_jobs")
    status = models.CharField(max_length=12, choices=STATUS_CHOICES, default="queued")
    # Blank means "all kinds" - mirrors the original endpoint's optional ?kind= query param.
    kind = models.CharField(max_length=20, choices=BestEffort.KIND_CHOICES, blank=True, default="")
    # Null until the candidate-activity count is known, then climbs to total_items - same
    # shape as ExportJob/ImportJob's total_items/processed_items.
    total_items = models.IntegerField(null=True, blank=True)
    processed_items = models.IntegerField(default=0)
    error_message = models.CharField(max_length=500, blank=True, default="")
    created_at = models.DateTimeField(auto_now_add=True)
    completed_at = models.DateTimeField(null=True, blank=True)

    class Meta:
        ordering = ["-created_at"]

    def __str__(self) -> str:
        return f"{self.athlete_id} best-effort recompute ({self.status})"
