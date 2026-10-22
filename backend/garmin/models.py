from django.db import models

from accounts.models import User
from core.models import PrefixedIDModel
from uploads.models import UploadBatch

DEFAULT_SYNC_LIMIT = 50
MAX_SYNC_LIMIT = 100


class GarminConnection(PrefixedIDModel):
    """One Garmin Connect link per athlete. Today it holds a Personal API key (pull-on-request
    sync); OAuth tokens for the business-tier program get their own fields when that lands."""

    id_prefix = "gcn"

    owner = models.OneToOneField(User, on_delete=models.CASCADE, related_name="garmin_connection")
    # The Garmin account's email, which can differ from the Cadence login email.
    garmin_email = models.EmailField(max_length=320)
    # AES-256-GCM, "v1.<base64url(iv || ciphertext+tag)>" - never plaintext, never returned by the API.
    api_key_encrypted = models.TextField()
    # Personal keys are issued for 30/90/180/365 days; Garmin doesn't expose it, so the athlete enters it.
    key_expires_on = models.DateField(null=True, blank=True)
    last_synced_at = models.DateTimeField(null=True, blank=True)
    # Most activities one sync pulls (set in Preferences). Garmin documents no rate limit; 100 is our own ceiling.
    sync_limit = models.PositiveSmallIntegerField(default=DEFAULT_SYNC_LIMIT)
    created = models.DateTimeField(auto_now_add=True)
    updated = models.DateTimeField(auto_now=True)

    class Meta:
        constraints = [
            models.CheckConstraint(
                condition=models.Q(sync_limit__gte=1) & models.Q(sync_limit__lte=MAX_SYNC_LIMIT),
                name="garmin_connection_sync_limit_1_100",
            )
        ]

    def __str__(self) -> str:
        return f"{self.owner_id} -> {self.garmin_email}"


class GarminSyncRun(PrefixedIDModel):
    """One Garmin sync attempt, so the Import screen can show when it last ran and what it did."""

    id_prefix = "gsr"

    owner = models.ForeignKey(User, on_delete=models.CASCADE, related_name="garmin_sync_runs")
    started_at = models.DateTimeField()
    finished_at = models.DateTimeField(null=True, blank=True)
    found = models.IntegerField(default=0)
    already_synced = models.IntegerField(default=0)
    requested = models.IntegerField(default=0)
    failed = models.IntegerField(default=0)
    truncated = models.BooleanField(default=False)
    # Short message for a run that errored (e.g. Garmin rejected the key); never the key itself.
    error = models.TextField(blank=True, default="")
    # SET_NULL so clearing import history can't break a run.
    batch = models.ForeignKey(UploadBatch, null=True, blank=True, on_delete=models.SET_NULL, related_name="+")

    class Meta:
        indexes = [models.Index(fields=["owner", "-started_at"], name="garmin_run_owner_started_idx")]

    def __str__(self) -> str:
        return f"{self.owner_id} @ {self.started_at:%Y-%m-%d %H:%M}"


class GarminSyncedActivity(models.Model):
    """Every Garmin activity id ever pulled for an athlete. Doubles as the "already synced" check,
    and deliberately outlives both the Cadence activity (deleting one doesn't bring it back on the
    next sync) and the run (run history is pruned, these rows are not)."""

    owner = models.ForeignKey(User, on_delete=models.CASCADE, related_name="garmin_synced_activities")
    garmin_activity_id = models.BigIntegerField()
    start_time = models.DateTimeField()
    run = models.ForeignKey(
        GarminSyncRun, null=True, blank=True, on_delete=models.SET_NULL, related_name="synced_activities"
    )
    created = models.DateTimeField(auto_now_add=True)

    class Meta:
        constraints = [
            models.UniqueConstraint(fields=["owner", "garmin_activity_id"], name="garmin_synced_owner_activity_uniq")
        ]

    def __str__(self) -> str:
        return f"{self.owner_id}: {self.garmin_activity_id}"


class GarminWorkoutOp(PrefixedIDModel):
    """One thing to do on Garmin Connect, applied by the athlete's local bridge. The browser queues
    these; the bridge (using the athlete's own Garmin login, which Cadence never sees) fetches the
    pending ones, applies them in order and reports the Garmin ids back. Mirrors ``GarminWorkoutOp``
    in backend_java."""

    id_prefix = "gwo"

    KINDS = ("upsert_workout", "schedule", "reschedule", "unschedule")

    owner = models.ForeignKey(User, on_delete=models.CASCADE, related_name="garmin_workout_ops")
    kind = models.CharField(max_length=20)
    status = models.CharField(max_length=10, default="pending")
    # Plain columns, not foreign keys: an unschedule must outlive the scheduled workout it cancels.
    workout_id = models.CharField(max_length=40)
    scheduled_workout_id = models.CharField(max_length=40, blank=True, default="")
    op_date = models.DateField(null=True, blank=True)
    name = models.CharField(max_length=200, blank=True, default="")
    # The workout as a Garmin .tcx, generated in the browser (it needs the athlete's thresholds).
    tcx = models.TextField(blank=True, default="")
    garmin_workout_id = models.BigIntegerField(null=True, blank=True)
    garmin_schedule_id = models.BigIntegerField(null=True, blank=True)
    error = models.TextField(blank=True, default="")
    created = models.DateTimeField(auto_now_add=True)
    completed_at = models.DateTimeField(null=True, blank=True)

    class Meta:
        indexes = [models.Index(fields=["owner", "status", "created"], name="garmin_wop_owner_status_idx")]


class GarminWorkoutLink(models.Model):
    """Cadence workout -> the Garmin workout it was pushed as."""

    owner = models.ForeignKey(User, on_delete=models.CASCADE, related_name="garmin_workout_links")
    workout_id = models.CharField(max_length=40)
    garmin_workout_id = models.BigIntegerField()
    pushed_at = models.DateTimeField(auto_now=True)

    class Meta:
        constraints = [models.UniqueConstraint(fields=["owner", "workout_id"], name="garmin_workout_link_unique")]

    def __str__(self) -> str:
        return f"{self.workout_id} -> {self.garmin_workout_id}"


class GarminScheduleLink(models.Model):
    """Cadence scheduled workout -> its entry on the Garmin calendar."""

    owner = models.ForeignKey(User, on_delete=models.CASCADE, related_name="garmin_schedule_links")
    scheduled_workout_id = models.CharField(max_length=40)
    garmin_schedule_id = models.BigIntegerField()
    garmin_workout_id = models.BigIntegerField()
    scheduled_for = models.DateField()

    class Meta:
        constraints = [
            models.UniqueConstraint(fields=["owner", "scheduled_workout_id"], name="garmin_schedule_link_unique")
        ]

    def __str__(self) -> str:
        return f"{self.scheduled_workout_id} -> {self.garmin_schedule_id}"
