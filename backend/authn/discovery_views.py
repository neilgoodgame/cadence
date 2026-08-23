"""Hand-written OAuth discovery documents - django-oauth-toolkit has no built-in support for
either RFC (confirmed via grep: no ``oauth2_provider.urls.oidc`` import, no ``OIDC_*`` settings
anywhere in this project), unlike the Java backend where Spring Security/Spring Authorization
Server ship RFC 9728/8414 support out of the box. Both documents must be publicly fetchable per
their specs, so both views are unauthenticated.

See ``backend_java/docs/mcp-oauth.md`` and ``docs/mcp-oauth.md`` for the full authorization flow
these documents are discovered as part of.
"""

from typing import cast

from django.conf import settings
from rest_framework.decorators import api_view, authentication_classes, permission_classes
from rest_framework.permissions import AllowAny
from rest_framework.request import Request
from rest_framework.response import Response

_SCOPES_SUPPORTED = list(cast(dict[str, str], settings.OAUTH2_PROVIDER["SCOPES"]).keys())


@api_view(["GET"])
@authentication_classes([])
@permission_classes([AllowAny])
def oauth_authorization_server_metadata(request: Request) -> Response:
    """RFC 8414 Authorization Server Metadata - https://www.rfc-editor.org/rfc/rfc8414"""
    issuer = settings.OAUTH_ISSUER
    return Response(
        {
            "issuer": issuer,
            "authorization_endpoint": f"{issuer}/oauth/authorize/",
            "token_endpoint": f"{issuer}/oauth/token",
            "response_types_supported": ["code"],
            "grant_types_supported": ["authorization_code", "refresh_token"],
            "code_challenge_methods_supported": ["S256"],
            "token_endpoint_auth_methods_supported": ["client_secret_basic", "client_secret_post"],
            "scopes_supported": _SCOPES_SUPPORTED,
        }
    )


@api_view(["GET"])
@authentication_classes([])
@permission_classes([AllowAny])
def oauth_protected_resource_metadata(request: Request) -> Response:
    """RFC 9728 Protected Resource Metadata - https://www.rfc-editor.org/rfc/rfc9728"""
    issuer = settings.OAUTH_ISSUER
    return Response(
        {
            "resource": f"{issuer}/mcp",
            "authorization_servers": [issuer],
            "bearer_methods_supported": ["header"],
            "scopes_supported": _SCOPES_SUPPORTED,
        }
    )
