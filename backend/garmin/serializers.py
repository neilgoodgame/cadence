from datetime import UTC, datetime

from rest_framework import serializers

from .models import GarminConnection, GarminSyncedActivity, GarminSyncRun


class GarminConnectionSerializer(serializers.Serializer):
    """Response shape. Never carries the API key, in any form."""

    connected = serializers.BooleanField()
    email = serializers.EmailField(allow_null=True)
    key_expires_on = serializers.DateField(allow_null=True)
    last_synced_at = serializers.DateTimeField(allow_null=True)
    # When a sync last ran (successful or not), and the newest Garmin activity ever pulled.
    last_run_at = serializers.DateTimeField(allow_null=True)
    last_activity = serializers.DictField(allow_null=True)

    @staticmethod
    def not_connected() -> dict:
        return {
            "connected": False,
            "email": None,
            "key_expires_on": None,
            "last_synced_at": None,
            "last_run_at": None,
            "last_activity": None,
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

    def validate_key_expires_on(self, value):
        if value is not None and value < datetime.now(UTC).date():
            raise serializers.ValidationError("The key's expiry date is in the past.")
        return value


class GarminSyncRequestSerializer(serializers.Serializer):
    """Both optional. ``limit`` caps this run (range-checked against the server's configured max
    in the sync itself); ``since`` overrides the default window (day after the last sync, or 30
    days on the first)."""

    limit = serializers.IntegerField(required=False)
    since = serializers.DateField(required=False)
