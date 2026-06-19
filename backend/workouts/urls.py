from django.urls import path

from .views import WorkoutDetailView, WorkoutListView, WorkoutMatchListView

urlpatterns = [
    path("v1/workouts", WorkoutListView.as_view(), name="workout-list"),
    path("v1/workouts/<str:id>", WorkoutDetailView.as_view(), name="workout-detail"),
    path("v1/workouts/<str:id>/matches", WorkoutMatchListView.as_view(), name="workout-matches"),
]
