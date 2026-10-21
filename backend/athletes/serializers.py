from dataclasses import replace
from typing import Any

from rest_framework import serializers

from accounts.models import User
from core.race_distances import RACE_DISTANCE_BUCKETS
from courses.sections import ALGORITHMS, SectionSettings

from .models import BestEffortRecomputeJob, ZoneSet
from .zones import reference_for


class SectionSettingsSerializer(serializers.Serializer):
    """Partial: any subset of the settings, merged into the athlete's current ones. Ranges are
    checked on the merged result by SectionSettings.validate, so a pair like flat/steep is
    checked together."""

    algorithm = serializers.ChoiceField(choices=list(ALGORITHMS), required=False)
    flat_max_pct = serializers.FloatField(required=False)
    steep_min_pct = serializers.FloatField(required=False)
    fit_tolerance_m = serializers.FloatField(required=False)
    min_section_m = serializers.FloatField(required=False)
    keep_min_rise_m = serializers.FloatField(required=False)
    spike_window_m = serializers.FloatField(required=False)
    smoothing_window_m = serializers.FloatField(required=False)
    resample_step_m = serializers.FloatField(required=False)


class AthleteUpdateSerializer(serializers.ModelSerializer):
    best_effort_top_n = serializers.IntegerField(required=False, min_value=0, max_value=50)
    default_shoe_limit_km = serializers.IntegerField(required=False, min_value=0, max_value=5000)
    course_section_settings = SectionSettingsSerializer(required=False)

    def validate_course_section_settings(self, value: dict[str, Any]) -> SectionSettings:
        merged = replace(self.instance.section_settings(), **value)
        error = merged.validation_error()
        if error is not None:
            raise serializers.ValidationError(error)
        return merged

    def validate_race_block_week_defaults(self, value: dict[str, Any]) -> dict[str, Any]:
        from races.serializers import MAX_BLOCK_PLAN_WEEKS

        known_keys = {bucket.key for bucket in RACE_DISTANCE_BUCKETS}
        for key, weeks in value.items():
            if key not in known_keys:
                raise serializers.ValidationError(f'Unknown race distance bucket "{key}".')
            if not isinstance(weeks, int) or not (1 <= weeks <= MAX_BLOCK_PLAN_WEEKS):
                raise serializers.ValidationError(f'"{key}" must be an integer between 1 and {MAX_BLOCK_PLAN_WEEKS}.')
        return {**self.instance.race_block_week_defaults, **value}

    def update(self, instance: User, validated_data: dict[str, Any]) -> User:
        settings = validated_data.pop("course_section_settings", None)
        if settings is not None:
            instance.set_section_settings(settings)
        return super().update(instance, validated_data)

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
            "ftp_calculation_method",
            "running_power_source",
            "rename_matched_activities",
            "append_match_date_to_name",
            "copy_matched_workout_tags",
            "lap_source",
            "default_shoe_limit_km",
            "threshold_warning_days",
            "decoupling_vi_limit_bike",
            "decoupling_vi_limit_run",
            "decoupling_if_limit",
            "decoupling_min_steady_minutes",
            "decoupling_warmup_minutes",
            "decoupling_use_workout_warmup",
            "decoupling_warm_air_temp",
            "decoupling_warm_skin_temp",
            "decoupling_hot_air_temp",
            "decoupling_hot_skin_temp",
            "course_section_settings",
            "race_block_weeks",
            "race_key_session_cap",
            "race_block_week_defaults",
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
                "ftp_calculation_method",
                "running_power_source",
                "rename_matched_activities",
                "append_match_date_to_name",
                "copy_matched_workout_tags",
                "lap_source",
                "threshold_warning_days",
                "decoupling_vi_limit_bike",
                "decoupling_vi_limit_run",
                "decoupling_if_limit",
                "decoupling_min_steady_minutes",
                "decoupling_warmup_minutes",
                "decoupling_use_workout_warmup",
                "decoupling_warm_air_temp",
                "decoupling_warm_skin_temp",
                "decoupling_hot_air_temp",
                "decoupling_hot_skin_temp",
                "race_block_weeks",
                "race_key_session_cap",
                "race_block_week_defaults",
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
