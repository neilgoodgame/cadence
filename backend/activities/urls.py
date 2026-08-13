from django.urls import path

from uploads.views import ActivityBatchUploadView

from .views import (
    ActivityCommentDetailView,
    ActivityCommentListView,
    ActivityDetailView,
    ActivityListView,
    ActivityTagView,
    ActivityUntagView,
    CurvesView,
    InferWorkoutView,
    LapListView,
    RecomputeActivityStatsView,
    RecomputeActivityTssView,
    StreamsView,
    TagDetailView,
    TagListView,
)

urlpatterns = [
    path("v1/activities", ActivityListView.as_view(), name="activity-list"),
    path("v1/activities/batch", ActivityBatchUploadView.as_view(), name="activity-batch-upload"),
    path("v1/activities/<str:id>", ActivityDetailView.as_view(), name="activity-detail"),
    path("v1/activities/<str:id>/recompute-tss", RecomputeActivityTssView.as_view(), name="activity-recompute-tss"),
    path(
        "v1/activities/<str:id>/recompute-stats",
        RecomputeActivityStatsView.as_view(),
        name="activity-recompute-stats",
    ),
    path("v1/activities/<str:id>/laps", LapListView.as_view(), name="activity-laps"),
    path("v1/activities/<str:id>/infer-workout", InferWorkoutView.as_view(), name="activity-infer-workout"),
    path("v1/activities/<str:id>/streams", StreamsView.as_view(), name="activity-streams"),
    path("v1/activities/<str:id>/curves", CurvesView.as_view(), name="activity-curves"),
    path("v1/activities/<str:id>/comments", ActivityCommentListView.as_view(), name="activity-comments"),
    path(
        "v1/activities/<str:id>/comments/<str:comment_id>",
        ActivityCommentDetailView.as_view(),
        name="activity-comment-detail",
    ),
    path("v1/tags", TagListView.as_view(), name="tag-list"),
    path("v1/tags/<str:id>", TagDetailView.as_view(), name="tag-detail"),
    path("v1/activities/<str:id>/tags", ActivityTagView.as_view(), name="activity-tags"),
    path("v1/activities/<str:id>/tags/<str:tag_id>", ActivityUntagView.as_view(), name="activity-untag"),
]
