from django.urls import path

from .views import (
    WorkoutDetailView,
    WorkoutFolderDetailView,
    WorkoutFolderListView,
    WorkoutListView,
    WorkoutMatchListView,
)

urlpatterns = [
    path("v1/workouts", WorkoutListView.as_view(), name="workout-list"),
    path("v1/workouts/<str:id>", WorkoutDetailView.as_view(), name="workout-detail"),
    path("v1/workouts/<str:id>/matches", WorkoutMatchListView.as_view(), name="workout-matches"),
    path("v1/workout-folders", WorkoutFolderListView.as_view(), name="workout-folder-list"),
    path("v1/workout-folders/<str:id>", WorkoutFolderDetailView.as_view(), name="workout-folder-detail"),
]
