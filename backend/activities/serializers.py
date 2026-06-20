from rest_framework import serializers

from .models import Activity, BestEffort, DurationCurve, Lap, Tag


class ActivitySerializer(serializers.ModelSerializer):
    tags = serializers.SerializerMethodField()

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
            "tags",
            "workout_id",
            "bike_id",
            "shoe_id",
        ]

    def get_tags(self, obj):
        return list(obj.tags.order_by("name").values_list("name", flat=True))


class ActivityUpdateSerializer(serializers.Serializer):
    name = serializers.CharField(required=False)
    sport = serializers.ChoiceField(choices=Activity.SPORT_CHOICES, required=False)
    workout_id = serializers.CharField(required=False, allow_null=True)
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

    def validate(self, attrs):
        if not attrs.get("tag_id") and not attrs.get("name"):
            raise serializers.ValidationError("Provide either tag_id or name.")
        return attrs
