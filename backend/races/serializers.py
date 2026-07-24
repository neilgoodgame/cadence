from rest_framework import serializers

from .models import Race


class RaceSerializer(serializers.ModelSerializer):
    goal_time = serializers.DurationField(allow_null=True, required=False)
    result_time = serializers.DurationField(allow_null=True, required=False)

    class Meta:
        model = Race
        fields = [
            "id",
            "athlete_id",
            "name",
            "date",
            "sport",
            "distance_km",
            "goal_time",
            "result_time",
            "activity_id",
            "notes",
        ]
        read_only_fields = ["id", "athlete_id"]


class RaceCreateSerializer(serializers.ModelSerializer):
    goal_time = serializers.DurationField(allow_null=True, required=False)
    result_time = serializers.DurationField(allow_null=True, required=False)

    class Meta:
        model = Race
        fields = ["name", "date", "sport", "distance_km", "goal_time", "result_time", "activity_id", "notes"]
        extra_kwargs = {
            "sport": {"required": False},
            "distance_km": {"required": False},
            "goal_time": {"required": False},
            "result_time": {"required": False},
            "activity_id": {"required": False},
            "notes": {"required": False},
        }


class RaceUpdateSerializer(serializers.ModelSerializer):
    goal_time = serializers.DurationField(allow_null=True, required=False)
    result_time = serializers.DurationField(allow_null=True, required=False)

    class Meta:
        model = Race
        fields = ["name", "date", "sport", "distance_km", "goal_time", "result_time", "activity_id", "notes"]
        extra_kwargs = {field: {"required": False} for field in fields}
