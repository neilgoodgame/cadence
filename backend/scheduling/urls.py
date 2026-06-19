from django.urls import path

from .views import CalendarView, ScheduledWorkoutDetailView, ScheduledWorkoutListCreateView

urlpatterns = [
    path("v1/calendar", CalendarView.as_view(), name="calendar"),
    path("v1/scheduled-workouts", ScheduledWorkoutListCreateView.as_view(), name="scheduled-workout-list"),
    path("v1/scheduled-workouts/<str:id>", ScheduledWorkoutDetailView.as_view(), name="scheduled-workout-detail"),
]
