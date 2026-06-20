from django.db import models

from accounts.models import User
from activities.models import Activity
from core.models import PrefixedIDModel
from workouts.models import Workout


class ScheduledWorkout(PrefixedIDModel):
    id_prefix = "sch"

    TIME_OF_DAY_CHOICES = [
        ("AM", "AM"),
        ("MID", "MID"),
        ("PM", "PM"),
    ]
    STATUS_CHOICES = [
        ("planned", "Planned"),
        ("completed", "Completed"),
        ("missed", "Missed"),
    ]

    workout = models.ForeignKey(Workout, on_delete=models.CASCADE, related_name="scheduled_workouts")
    athlete = models.ForeignKey(User, on_delete=models.CASCADE, related_name="scheduled_workouts")
    # Null when the athlete scheduled it for themselves; set to the coach's id
    # when a coach assigned it onto the athlete's plan.
    assigned_by = models.ForeignKey(
        User, null=True, blank=True, on_delete=models.SET_NULL, related_name="assigned_scheduled_workouts"
    )
    date = models.DateField()
    time_of_day = models.CharField(max_length=3, choices=TIME_OF_DAY_CHOICES, blank=True, default="")
    status = models.CharField(max_length=10, choices=STATUS_CHOICES, default="planned")
    activity = models.ForeignKey(
        Activity, null=True, blank=True, on_delete=models.SET_NULL, related_name="scheduled_workout_matches"
    )

    class Meta:
        ordering = ["date"]

    def __str__(self) -> str:
        return f"{self.workout_id} on {self.date}"
