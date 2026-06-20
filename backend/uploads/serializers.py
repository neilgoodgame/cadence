from django.db.models import Count
from rest_framework import serializers

from .models import Upload, UploadBatch


class UploadSerializer(serializers.ModelSerializer):
    object = serializers.SerializerMethodField()
    error = serializers.SerializerMethodField()

    class Meta:
        model = Upload
        fields = [
            "id",
            "object",
            "status",
            "progress",
            "filename",
            "activity_id",
            "error",
            "received_at",
            "completed_at",
        ]

    def get_object(self, obj):
        return "upload"

    def get_error(self, obj):
        if obj.status != "failed":
            return None
        return {"code": obj.error_code, "message": obj.error_message}


class UploadBatchSerializer(serializers.ModelSerializer):
    object = serializers.SerializerMethodField()
    progress = serializers.SerializerMethodField()
    counts = serializers.SerializerMethodField()
    uploads = serializers.SerializerMethodField()

    class Meta:
        model = UploadBatch
        fields = ["id", "object", "status", "filename", "progress", "counts", "uploads", "received_at", "completed_at"]

    def get_object(self, obj):
        return "upload_batch"

    def _counts(self, obj):
        counts = {"total": 0, "ready": 0, "processing": 0, "failed": 0, "duplicate": 0}
        for row in obj.uploads.values("status").annotate(n=Count("id")):
            counts["total"] += row["n"]
            if row["status"] == "queued":
                counts["processing"] += row["n"]
            elif row["status"] in counts:
                counts[row["status"]] += row["n"]
        return counts

    def get_counts(self, obj):
        return self._counts(obj)

    def get_progress(self, obj):
        counts = self._counts(obj)
        if counts["total"] == 0:
            return 0.0
        settled = counts["total"] - counts["processing"]
        return round(settled / counts["total"], 2)

    def get_uploads(self, obj):
        return UploadSerializer(obj.uploads.all(), many=True).data
