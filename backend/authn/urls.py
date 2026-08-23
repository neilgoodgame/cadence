from django.urls import path

from .discovery_views import oauth_authorization_server_metadata, oauth_protected_resource_metadata
from .login_views import MCPLoginView
from .views import CreateJwtView, JwksView

urlpatterns = [
    path("v1/auth/jwt", CreateJwtView.as_view(), name="create-jwt"),
    path(".well-known/jwks.json", JwksView.as_view(), name="jwks"),
    path("oauth/login/", MCPLoginView.as_view(), name="authn-login"),
    path(
        ".well-known/oauth-authorization-server",
        oauth_authorization_server_metadata,
        name="oauth-authorization-server-metadata",
    ),
    path(
        ".well-known/oauth-protected-resource",
        oauth_protected_resource_metadata,
        name="oauth-protected-resource-metadata",
    ),
]
