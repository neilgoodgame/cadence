from typing import Any

from rest_framework import serializers

from .models import Activity, BestEffort, DurationCurve, Lap, Tag


class ActivitySerializer(serializers.ModelSerializer):
    tags = serializers.SerializerMethodField()
    child_activity_ids = serializers.SerializerMethodField()
    duplicate_activity_ids = serializers.SerializerMethodField()

    class Meta:
        model = Activity
        fields = [
            "id",
            "athlete_id",
            "sport",
            "environment",
            "has_gps",
            "name",
            "start_date",
            "source",
            "device",
            "moving_time",
            "distance_km",
            "distance_source",
            "avg_power",
            "norm_power",
            "intensity",
            "tss",
            "avg_hr",
            "max_hr",
            "ascent",
            "start_weight_kg",
            "end_weight_kg",
            "fluids_ml",
            "avg_air_temp",
            "avg_humidity",
            "aerobic_training_effect",
            "anaerobic_training_effect",
            "training_effect_label",
            "tags",
            "workout_id",
            "bike_id",
            "shoe_id",
            "parent_activity_id",
            "child_activity_ids",
            "primary_activity_id",
            "duplicate_activity_ids",
        ]

    def get_tags(self, obj: Activity) -> list[str]:
        return list(obj.tags.order_by("name").values_list("name", flat=True))

    def get_child_activity_ids(self, obj: Activity) -> list[str]:
        if obj.sport != "multisport":
            return []
        return list(obj.child_activities.order_by("start_date").values_list("id", flat=True))

    def get_duplicate_activity_ids(self, obj: Activity) -> list[str]:
        # A duplicate can never itself be a primary (chains are rejected on update),
        # so skip the lookup for anything already linked to a primary.
        if obj.primary_activity_id:
            return []
        return list(obj.duplicate_activities.order_by("start_date").values_list("id", flat=True))


class ActivityUpdateSerializer(serializers.Serializer):
    name = serializers.CharField(required=False)
    sport = serializers.ChoiceField(choices=Activity.SPORT_CHOICES, required=False)
    workout_id = serializers.CharField(required=False, allow_null=True)
    primary_activity_id = serializers.CharField(required=False, allow_null=True)
    start_weight_kg = serializers.FloatField(required=False, allow_null=True)
    end_weight_kg = serializers.FloatField(required=False, allow_null=True)
    fluids_ml = serializers.IntegerField(required=False, allow_null=True)
    avg_air_temp = serializers.FloatField(required=False, allow_null=True)
    avg_humidity = serializers.IntegerField(required=False, allow_null=True)


class LapSerializer(serializers.ModelSerializer):
    class Meta:
        model = Lap
        fields = ["index", "duration", "distance_km", "avg_hr", "avg_power"]


class TagSerializer(serializers.ModelSerializer):
    class Meta:
        model = Tag
        fields = ["id", "name", "origin", "color"]
        read_only_fields = ["id", "origin"]


class DurationCurveSerializer(serializers.ModelSerializer):
    class Meta:
        model = DurationCurve
        fields = ["metric", "extends_to", "points"]


class BestEffortSerializer(serializers.ModelSerializer):
    activity_id = serializers.CharField(read_only=True)

    class Meta:
        model = BestEffort
        fields = ["window", "value", "unit", "date", "activity_id"]


class TagAttachSerializer(serializers.Serializer):
    tag_id = serializers.CharField(required=False)
    name = serializers.CharField(required=False)

    def validate(self, attrs: dict[str, Any]) -> dict[str, Any]:
        if not attrs.get("tag_id") and not attrs.get("name"):
            raise serializers.ValidationError("Provide either tag_id or name.")
        return attrs
