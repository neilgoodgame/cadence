from rest_framework import serializers

from accounts.models import User

from .models import BestEffortRecomputeJob, ZoneSet
from .zones import reference_for


class AthleteUpdateSerializer(serializers.ModelSerializer):
    best_effort_top_n = serializers.IntegerField(required=False, min_value=0, max_value=50)

    class Meta:
        model = User
        fields = [
            "name",
            "age",
            "weight_kg",
            "ftp",
            "critical_run_power",
            "threshold_pace",
            "lthr",
            "max_hr",
            "resting_hr",
            "best_effort_top_n",
            "rename_matched_activities",
            "append_match_date_to_name",
            "copy_matched_workout_tags",
        ]
        extra_kwargs = {
            field: {"required": False}
            for field in [
                "name",
                "age",
                "weight_kg",
                "ftp",
                "critical_run_power",
                "threshold_pace",
                "lthr",
                "max_hr",
                "resting_hr",
                "rename_matched_activities",
                "append_match_date_to_name",
                "copy_matched_workout_tags",
            ]
        }


class ZoneSerializer(serializers.Serializer):
    name = serializers.CharField()
    low_pct = serializers.FloatField()
    high_pct = serializers.FloatField()


class ZoneSetSerializer(serializers.Serializer):
    type = serializers.CharField()
    reference = serializers.SerializerMethodField()
    zones = ZoneSerializer(many=True)

    def get_reference(self, obj: ZoneSet) -> int | None:
        return reference_for(obj.athlete, obj.type, activity=self.context.get("activity"))


class ZoneSetReplaceSerializer(serializers.Serializer):
    zones = ZoneSerializer(many=True)


class FitnessPointSerializer(serializers.Serializer):
    date = serializers.DateField()
    ctl = serializers.FloatField()
    atl = serializers.FloatField()
    tsb = serializers.FloatField()


class BestEffortRecomputeJobSerializer(serializers.ModelSerializer):
    object = serializers.SerializerMethodField()

    class Meta:
        model = BestEffortRecomputeJob
        fields = [
            "id",
            "object",
            "status",
            "kind",
            "total_items",
            "processed_items",
            "error_message",
            "created_at",
            "completed_at",
        ]

    def get_object(self, obj: BestEffortRecomputeJob) -> str:
        return "best_effort_recompute_job"
