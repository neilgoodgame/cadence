from datetime import UTC, datetime

from rest_framework import serializers

from .models import MAX_SYNC_LIMIT, GarminConnection, GarminSyncedActivity, GarminSyncRun


class GarminConnectionSerializer(serializers.Serializer):
    """Response shape. Never carries the API key, in any form."""

    connected = serializers.BooleanField()
    email = serializers.EmailField(allow_null=True)
    key_expires_on = serializers.DateField(allow_null=True)
    last_synced_at = serializers.DateTimeField(allow_null=True)
    # When a sync last ran (successful or not), and the newest Garmin activity ever pulled.
    last_run_at = serializers.DateTimeField(allow_null=True)
    last_activity = serializers.DictField(allow_null=True)
    sync_limit = serializers.IntegerField(allow_null=True)

    @staticmethod
    def not_connected() -> dict:
        return {
            "connected": False,
            "email": None,
            "key_expires_on": None,
            "last_synced_at": None,
            "last_run_at": None,
            "last_activity": None,
            "sync_limit": None,
        }

    @staticmethod
    def from_connection(connection: GarminConnection) -> dict:
        last_run = GarminSyncRun.objects.filter(owner=connection.owner).order_by("-started_at").first()
        last_activity = GarminSyncedActivity.objects.filter(owner=connection.owner).order_by("-start_time").first()
        return {
            "connected": True,
            "email": connection.garmin_email,
            "key_expires_on": connection.key_expires_on,
            "last_synced_at": connection.last_synced_at,
            "last_run_at": last_run.started_at if last_run else None,
            "last_activity": (
                {"garmin_activity_id": last_activity.garmin_activity_id, "start_time": last_activity.start_time}
                if last_activity
                else None
            ),
            "sync_limit": connection.sync_limit,
        }


class GarminConnectionWriteSerializer(serializers.Serializer):
    """``api_key`` is required the first time and optional afterwards (omit it to leave the stored
    key alone while editing the email or expiry). ``email`` defaults to the athlete's own login
    email when blank."""

    api_key = serializers.CharField(
        required=False, allow_blank=True, min_length=8, max_length=512, trim_whitespace=True
    )
    email = serializers.EmailField(required=False, allow_blank=True, max_length=320)
    key_expires_on = serializers.DateField(required=False, allow_null=True)
    # How many activities one sync pulls; omit to leave it alone (50 for a new connection).
    sync_limit = serializers.IntegerField(required=False, min_value=1, max_value=MAX_SYNC_LIMIT)

    def validate_key_expires_on(self, value):
        if value is not None and value < datetime.now(UTC).date():
            raise serializers.ValidationError("The key's expiry date is in the past.")
        return value


class GarminWorkoutOpSerializer(serializers.Serializer):
    """What the browser asks the bridge to do. ``workout_id`` is always the Cadence workout.
    upsert_workout carries the ``tcx``; schedule and reschedule carry ``scheduled_workout_id`` and
    ``date``; unschedule carries ``scheduled_workout_id``."""

    kind = serializers.ChoiceField(choices=["upsert_workout", "schedule", "reschedule", "unschedule"])
    workout_id = serializers.CharField(max_length=40)
    scheduled_workout_id = serializers.CharField(max_length=40, required=False, allow_blank=True)
    date = serializers.DateField(required=False, allow_null=True)
    name = serializers.CharField(max_length=200, required=False, allow_blank=True, allow_null=True)
    tcx = serializers.CharField(max_length=500_000, required=False, allow_blank=True, allow_null=True)

    def to_internal_value(self, data):
        # The bridge and the Java backend use UPPER_CASE-insensitive kinds; accept either here too.
        if isinstance(data, dict) and isinstance(data.get("kind"), str):
            data = {**data, "kind": data["kind"].lower()}
        return super().to_internal_value(data)


class GarminWorkoutOpResultSerializer(serializers.Serializer):
    """The bridge's report for an operation: ``status`` is done or failed."""

    status = serializers.ChoiceField(choices=["done", "failed"])
    garmin_workout_id = serializers.IntegerField(required=False, allow_null=True)
    garmin_schedule_id = serializers.IntegerField(required=False, allow_null=True)
    error = serializers.CharField(max_length=2000, required=False, allow_blank=True, allow_null=True)

    def to_internal_value(self, data):
        if isinstance(data, dict) and isinstance(data.get("status"), str):
            data = {**data, "status": data["status"].lower()}
        return super().to_internal_value(data)


class GarminSyncRequestSerializer(serializers.Serializer):
    """All optional. ``limit`` caps this run (1-100; defaults to the athlete's saved sync limit) and
    ``since`` overrides the default window (day after the last sync, or 30 days on the first).
    ``latest`` syncs only Garmin's newest activity; only then may the session details (weights,
    fluids, shoe) be given, exactly as when importing a single file."""

    limit = serializers.IntegerField(required=False, min_value=1, max_value=MAX_SYNC_LIMIT)
    since = serializers.DateField(required=False)
    latest = serializers.BooleanField(required=False, default=False)
    weight_before_kg = serializers.FloatField(required=False, allow_null=True)
    weight_after_kg = serializers.FloatField(required=False, allow_null=True)
    fluids_ml = serializers.IntegerField(required=False, allow_null=True)
    shoe_id = serializers.CharField(required=False, allow_null=True, allow_blank=True)
