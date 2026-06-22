from rest_framework import serializers

from .models import ScheduledWorkout


class ScheduledWorkoutSerializer(serializers.ModelSerializer):
    class Meta:
        model = ScheduledWorkout
        fields = [
            "id",
            "workout_id",
            "athlete_id",
            "assigned_by",
            "date",
            "time_of_day",
            "status",
            "activity_id",
        ]


class ScheduleWorkoutCreateSerializer(serializers.Serializer):
    workout_id = serializers.CharField()
    athlete_id = serializers.CharField()
    date = serializers.DateField()
    time_of_day = serializers.ChoiceField(choices=ScheduledWorkout.TIME_OF_DAY_CHOICES, required=False)


class ScheduledWorkoutUpdateSerializer(serializers.Serializer):
    date = serializers.DateField(required=False)
    activity_id = serializers.CharField(required=False)
