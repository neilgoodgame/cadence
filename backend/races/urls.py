from django.urls import path

from .views import (
    RaceBlockView,
    RaceCommentDetailView,
    RaceCommentListView,
    RaceDetailView,
    RaceListCreateView,
    RaceSplitsView,
    RaceWeatherView,
)

urlpatterns = [
    path("v1/races", RaceListCreateView.as_view(), name="race-list"),
    path("v1/races/<str:id>", RaceDetailView.as_view(), name="race-detail"),
    path("v1/races/<str:id>/block", RaceBlockView.as_view(), name="race-block"),
    path("v1/races/<str:id>/splits", RaceSplitsView.as_view(), name="race-splits"),
    path("v1/races/<str:id>/weather", RaceWeatherView.as_view(), name="race-weather"),
    path("v1/races/<str:id>/comments", RaceCommentListView.as_view(), name="race-comment-list"),
    path("v1/races/<str:id>/comments/<str:comment_id>", RaceCommentDetailView.as_view(), name="race-comment-detail"),
]
