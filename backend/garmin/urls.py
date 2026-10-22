from django.urls import path

from .views import GarminConnectionView, GarminSyncHistoryView, GarminSyncView

urlpatterns = [
    path("v1/garmin/connection", GarminConnectionView.as_view(), name="garmin-connection"),
    path("v1/garmin/sync", GarminSyncView.as_view(), name="garmin-sync"),
    path("v1/garmin/syncs", GarminSyncHistoryView.as_view(), name="garmin-syncs"),
]
