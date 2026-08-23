"""Shared base class for every Cadence MCP tool - mirrors the Java backend's
mcp/dispatch/McpToolAuthorizer.java. Every tool method's first line must call
``self._require_scope(...)``, checking the *token's* granted scopes - a separate mechanism from,
and always in addition to, the athlete-ownership check (``_require_read``/``_require_write``,
mirroring core/permissions.py's ``user_may_read``/``user_may_write`` exactly, the same functions
every REST view already uses).

Every helper below is underscore-prefixed deliberately, not by convention alone: django-mcp-server's
``MCPToolset`` auto-registers every non-underscore-prefixed method as its own MCP tool (confirmed
live - without the underscore, every subclass's inherited ``require_scope``/``require_read``/etc.
got registered as bogus duplicate tools, logged as "Tool already exists" warnings at startup).

``self.request`` inside an ``MCPToolset`` method is the real DRF ``Request`` that
``DJANGO_MCP_AUTHENTICATION_CLASSES`` (``authn.mcp_auth.McpOAuth2Authentication``) already
authenticated before dispatch reached the tool - confirmed live: ``self.request.user`` and
``self.request.auth`` are populated exactly like in a normal ``APIView``, so
``core.auth_context``'s helpers (built for DRF ``Request`` objects) work unmodified here.
"""

from mcp_server import MCPToolset
from rest_framework.exceptions import PermissionDenied

from core.auth_context import get_effective_athlete_id, get_request_scopes
from core.permissions import user_may_read, user_may_write


class ScopedMCPToolset(MCPToolset):
    def _require_scope(self, scope: str) -> None:
        if scope not in get_request_scopes(self.request):
            raise PermissionDenied(f"This action requires the '{scope}' scope.")

    def _effective_athlete_id(self) -> str:
        """The caller's own athlete id. No MCP tool exposes an athlete_id param - v1 is
        self-only, no coach-for-athlete delegation, matching the Java backend exactly."""
        _, athlete_id = get_effective_athlete_id(self.request)
        return athlete_id

    def _require_read(self, athlete_id: str) -> None:
        sub, _ = get_effective_athlete_id(self.request)
        if not user_may_read(sub, athlete_id):
            raise PermissionDenied("You do not have access to that athlete's data.")

    def _require_write(self, athlete_id: str) -> None:
        sub, _ = get_effective_athlete_id(self.request)
        if not user_may_write(sub, athlete_id):
            raise PermissionDenied("You do not have write access to that athlete's data.")
