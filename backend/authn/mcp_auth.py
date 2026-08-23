from django.conf import settings
from django.http import HttpRequest
from oauth2_provider.contrib.rest_framework import OAuth2Authentication


class McpOAuth2Authentication(OAuth2Authentication):
    """Same OAuth2 bearer-token authentication used everywhere else in this API, but with an
    MCP-specific 401 challenge: per the MCP authorization spec, an unauthenticated ``/mcp``
    request must return ``WWW-Authenticate: Bearer resource_metadata="..."`` pointing at this
    server's RFC 9728 protected-resource metadata document, so Claude's client can discover the
    authorization server from the 401 itself rather than falling back to probing ``.well-known``
    paths blind. Every other endpoint keeps the default ``Bearer realm="api"`` challenge
    unchanged, since ``django-mcp-server`` applies ``DJANGO_MCP_AUTHENTICATION_CLASSES`` only to
    its own views (``mcp_server/urls.py``), not the global ``REST_FRAMEWORK`` auth chain - mirrors
    the Java backend's ``McpAuthenticationEntryPoint``, which is likewise wired only into
    ``/mcp``'s own entry point (see ``docs/mcp-oauth.md``).
    """

    def authenticate_header(self, request: HttpRequest) -> str:
        return f'Bearer resource_metadata="{settings.OAUTH_ISSUER}/.well-known/oauth-protected-resource"'
