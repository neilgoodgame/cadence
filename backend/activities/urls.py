from django.urls import path
from uploads.views import ActivityBatchUploadView

from .views import (
    ActivityDetailView,
    ActivityListView,
    ActivityTagView,
    ActivityUntagView,
    CurvesView,
    LapListView,
    StreamsView,
    TagListView,
)

urlpatterns = [
    path("v1/activities", ActivityListView.as_view(), name="activity-list"),
    path("v1/activities/batch", ActivityBatchUploadView.as_view(), name="activity-batch-upload"),
    path("v1/activities/<str:id>", ActivityDetailView.as_view(), name="activity-detail"),
    path("v1/activities/<str:id>/laps", LapListView.as_view(), name="activity-laps"),
    path("v1/activities/<str:id>/streams", StreamsView.as_view(), name="activity-streams"),
    path("v1/activities/<str:id>/curves", CurvesView.as_view(), name="activity-curves"),
    path("v1/tags", TagListView.as_view(), name="tag-list"),
    path("v1/activities/<str:id>/tags", ActivityTagView.as_view(), name="activity-tags"),
    path("v1/activities/<str:id>/tags/<str:tag_id>", ActivityUntagView.as_view(), name="activity-untag"),
]
