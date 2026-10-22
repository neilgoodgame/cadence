from django.urls import path

from .views import (
    GarminConnectionView,
    GarminSyncHistoryView,
    GarminSyncView,
    GarminWorkoutLinksView,
    GarminWorkoutOpDetailView,
    GarminWorkoutOpResultView,
    GarminWorkoutOpsView,
)

urlpatterns = [
    path("v1/garmin/connection", GarminConnectionView.as_view(), name="garmin-connection"),
    path("v1/garmin/sync", GarminSyncView.as_view(), name="garmin-sync"),
    path("v1/garmin/syncs", GarminSyncHistoryView.as_view(), name="garmin-syncs"),
    path("v1/garmin/workout-ops", GarminWorkoutOpsView.as_view(), name="garmin-workout-ops"),
    path("v1/garmin/workout-ops/<str:op_id>", GarminWorkoutOpDetailView.as_view(), name="garmin-workout-op"),
    path(
        "v1/garmin/workout-ops/<str:op_id>/result",
        GarminWorkoutOpResultView.as_view(),
        name="garmin-workout-op-result",
    ),
    path("v1/garmin/workout-links", GarminWorkoutLinksView.as_view(), name="garmin-workout-links"),
]
