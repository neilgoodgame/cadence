from rest_framework import serializers

from .models import ScheduledWorkout


class ScheduledWorkoutSerializer(serializers.ModelSerializer):
    assigned_by_name = serializers.SerializerMethodField()
    assigned_by_is_virtual = serializers.SerializerMethodField()

    class Meta:
        model = ScheduledWorkout
        fields = [
            "id",
            "workout_id",
            "athlete_id",
            "assigned_by",
            "assigned_by_name",
            "assigned_by_is_virtual",
            "date",
            "time_of_day",
            "status",
            "activity_id",
            "notes",
        ]

    def get_assigned_by_name(self, obj: ScheduledWorkout) -> str | None:
        return obj.assigned_by.name if obj.assigned_by else None

    def get_assigned_by_is_virtual(self, obj: ScheduledWorkout) -> bool:
        return obj.assigned_by.is_virtual if obj.assigned_by else False


class ScheduleWorkoutCreateSerializer(serializers.Serializer):
    workout_id = serializers.CharField()
    athlete_id = serializers.CharField()
    date = serializers.DateField()
    time_of_day = serializers.ChoiceField(choices=ScheduledWorkout.TIME_OF_DAY_CHOICES, required=False)
    notes = serializers.CharField(required=False, allow_blank=True, max_length=500)


class ScheduledWorkoutUpdateSerializer(serializers.Serializer):
    date = serializers.DateField(required=False)
    activity_id = serializers.CharField(required=False)
    time_of_day = serializers.ChoiceField(choices=ScheduledWorkout.TIME_OF_DAY_CHOICES, required=False)
    notes = serializers.CharField(required=False, allow_blank=True, max_length=500)
