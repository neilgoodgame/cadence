from rest_framework import serializers

from .models import ExportJob, ImportJob


class ExportJobSerializer(serializers.ModelSerializer):
    object = serializers.SerializerMethodField()

    class Meta:
        model = ExportJob
        fields = [
            "id",
            "object",
            "status",
            "current_step",
            "total_items",
            "processed_items",
            "file_size_bytes",
            "error_message",
            "created_at",
            "completed_at",
        ]

    def get_object(self, obj: ExportJob) -> str:
        return "export"


class ImportCountsSerializer(serializers.Serializer):
    activities_imported = serializers.IntegerField()
    races_imported = serializers.IntegerField()
    workouts_imported = serializers.IntegerField()
    scheduled_workouts_imported = serializers.IntegerField()
    bikes_imported = serializers.IntegerField()
    shoes_imported = serializers.IntegerField()
    components_imported = serializers.IntegerField()
    items_skipped = serializers.IntegerField()


class ImportJobSerializer(serializers.ModelSerializer):
    object = serializers.SerializerMethodField()
    counts = serializers.SerializerMethodField()

    class Meta:
        model = ImportJob
        fields = [
            "id",
            "object",
            "status",
            "current_step",
            "total_items",
            "processed_items",
            "counts",
            "error_message",
            "created_at",
            "completed_at",
        ]

    def get_object(self, obj: ImportJob) -> str:
        return "import"

    def get_counts(self, obj: ImportJob) -> dict:
        return ImportCountsSerializer(obj).data
