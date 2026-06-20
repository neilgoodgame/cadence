from rest_framework import serializers

from .models import Workout, WorkoutStep


class WorkoutStepSerializer(serializers.ModelSerializer):
    class Meta:
        model = WorkoutStep
        fields = ["kind", "end_type", "duration", "distance", "target_pct", "repeat"]
        extra_kwargs = {
            "duration": {"required": False, "allow_null": True},
            "distance": {"required": False, "allow_null": True},
            "target_pct": {"required": False, "allow_null": True},
            "repeat": {"required": False, "default": 1},
        }

    def validate(self, attrs):
        end_type = attrs.get("end_type")
        if end_type == "time" and not attrs.get("duration"):
            raise serializers.ValidationError({"duration": "duration is required when end_type is 'time'."})
        if end_type == "distance" and not attrs.get("distance"):
            raise serializers.ValidationError({"distance": "distance is required when end_type is 'distance'."})
        return attrs


class WorkoutSerializer(serializers.ModelSerializer):
    class Meta:
        model = Workout
        fields = ["id", "name", "sport", "type", "duration", "tss"]
        read_only_fields = ["id", "type", "duration", "tss"]


class WorkoutDetailSerializer(WorkoutSerializer):
    steps = WorkoutStepSerializer(many=True, read_only=True)

    class Meta(WorkoutSerializer.Meta):
        fields = WorkoutSerializer.Meta.fields + ["steps"]


class WorkoutCreateSerializer(serializers.Serializer):
    name = serializers.CharField()
    sport = serializers.ChoiceField(choices=Workout.SPORT_CHOICES)
    steps = WorkoutStepSerializer(many=True)


class WorkoutUpdateSerializer(serializers.Serializer):
    name = serializers.CharField(required=False)
    steps = WorkoutStepSerializer(many=True, required=False)


class WorkoutMatchSerializer(serializers.Serializer):
    activity_id = serializers.CharField()
    name = serializers.CharField()
    date = serializers.DateField()
    method = serializers.ChoiceField(choices=["auto", "manual"])
    confidence = serializers.FloatField(allow_null=True)
    compliance = serializers.FloatField(allow_null=True)
