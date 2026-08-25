from django.urls import path

from .views import (
    AccessTokenDetailView,
    AccessTokenListCreateView,
    AccessTokenRotateView,
    CoachAthleteDetailView,
    ContextsView,
    LoginView,
    MeView,
    RegisterView,
    ResendVerificationView,
    RosterListView,
    ShareDetailView,
    ShareListCreateView,
    VerifyEmailView,
    VirtualCoachCreateView,
)

urlpatterns = [
    path("v1/auth/register", RegisterView.as_view(), name="register"),
    path("v1/auth/login", LoginView.as_view(), name="login"),
    path("v1/auth/verify-email", VerifyEmailView.as_view(), name="verify-email"),
    path("v1/auth/resend-verification", ResendVerificationView.as_view(), name="resend-verification"),
    path("v1/me", MeView.as_view(), name="me"),
    path("v1/me/contexts", ContextsView.as_view(), name="me-contexts"),
    path("v1/auth/tokens", AccessTokenListCreateView.as_view(), name="access-tokens"),
    path("v1/auth/tokens/<str:id>", AccessTokenDetailView.as_view(), name="access-token-detail"),
    path("v1/auth/tokens/<str:id>/rotate", AccessTokenRotateView.as_view(), name="access-token-rotate"),
    path("v1/shares", ShareListCreateView.as_view(), name="shares"),
    # Must come before the <str:id> pattern below - Django's resolver matches the first pattern
    # in list order regardless of HTTP method, so "virtual-coach" would otherwise be swallowed
    # as an `id` by ShareDetailView (405, since it has no post()).
    path("v1/shares/virtual-coach", VirtualCoachCreateView.as_view(), name="virtual-coach"),
    path("v1/shares/<str:id>", ShareDetailView.as_view(), name="share-detail"),
    path("v1/coach/athletes", RosterListView.as_view(), name="coach-roster"),
    path("v1/coach/athletes/<str:id>", CoachAthleteDetailView.as_view(), name="coach-athlete-detail"),
]
