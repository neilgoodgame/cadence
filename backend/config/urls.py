from django.contrib import admin
from django.urls import include, path
from drf_spectacular.views import SpectacularAPIView, SpectacularSwaggerView
from oauth2_provider.views import TokenView

from core.views import healthcheck

urlpatterns = [
    path("admin/", admin.site.urls),
    path("healthz", healthcheck, name="healthcheck"),
    # openapi.yaml documents this without a trailing slash; oauth2_provider.urls
    # only registers "token/", which would otherwise 301 POST requests here.
    path("oauth/token", TokenView.as_view(), name="oauth-token"),
    path("oauth/", include("oauth2_provider.urls", namespace="oauth2_provider")),
    path("schema/", SpectacularAPIView.as_view(), name="schema"),
    path("schema/docs/", SpectacularSwaggerView.as_view(url_name="schema"), name="swagger-ui"),
    path("", include("accounts.urls")),
    path("", include("authn.urls")),
    path("", include("athletes.urls")),
    path("", include("gear.urls")),
    path("", include("workouts.urls")),
    path("", include("activities.urls")),
    path("", include("scheduling.urls")),
    path("", include("uploads.urls")),
    path("", include("webhooks.urls")),
]
