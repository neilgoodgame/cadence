from django.urls import path

from .views import (
    AccessTokenDetailView,
    AccessTokenListCreateView,
    AccessTokenRotateView,
    CoachAthleteDetailView,
    ContextsView,
    MeView,
    RegisterView,
    RosterListView,
    ShareDetailView,
    ShareListCreateView,
)

urlpatterns = [
    path("v1/auth/register", RegisterView.as_view(), name="register"),
    path("v1/me", MeView.as_view(), name="me"),
    path("v1/me/contexts", ContextsView.as_view(), name="me-contexts"),
    path("v1/auth/tokens", AccessTokenListCreateView.as_view(), name="access-tokens"),
    path("v1/auth/tokens/<str:id>", AccessTokenDetailView.as_view(), name="access-token-detail"),
    path("v1/auth/tokens/<str:id>/rotate", AccessTokenRotateView.as_view(), name="access-token-rotate"),
    path("v1/shares", ShareListCreateView.as_view(), name="shares"),
    path("v1/shares/<str:id>", ShareDetailView.as_view(), name="share-detail"),
    path("v1/coach/athletes", RosterListView.as_view(), name="coach-roster"),
    path("v1/coach/athletes/<str:id>", CoachAthleteDetailView.as_view(), name="coach-athlete-detail"),
]
