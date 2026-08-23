"""Shared constants/policy for the MCP server's OAuth2 client, used by Claude.ai/Claude Desktop's
remote-connector flow - a genuine third-party grant, unlike ``oauth_utils.py``'s first-party
client (which is trusted and bypasses the authorize/consent dance entirely via the password
grant), so this one is a real ``authorization-code`` Application with PKCE and consent required.

The Application row itself is seeded by a data migration
(``authn/migrations/0001_seed_mcp_oauth_application.py``), not created lazily here like the
first-party client is: nothing in oauth2-toolkit's stock ``AuthorizationView``/``TokenView`` calls
back into project code to create a client on demand - they look Applications up directly from the
database by ``client_id``, so the row has to already exist before the first real request.

The redirect URI is Anthropic's documented constant for hosted Claude surfaces (web/Desktop/
mobile/Cowork), not derived from CORS config - Claude is never same-origin with this API. No
Dynamic Client Registration is implemented or needed: per Anthropic's own docs
(claude.com/docs/connectors/building/authentication), a user adding this as a custom connector
pastes this client's id/secret into Claude's "Add custom connector -> Advanced settings" alongside
the server URL - a fully self-service path with no registration_endpoint required. Mirrors the
Java backend's ``McpClientConfig`` exactly in spirit (see ``backend_java/.../McpClientConfig.java``
and ``docs/mcp-oauth.md``).

``coach`` is deliberately excluded from this client's requested/default scope for now - whether an
AI assistant should be able to act on a coach's behalf against a shared athlete's data is a real
product question, left for a later phase. (django-oauth-toolkit has no per-Application scope
allowlist without an add-on, so this isn't enforced at the Application level - only by what's
actually requested/granted at authorize time.)
"""

MCP_CLIENT_ID = "cadence-mcp"

# Anthropic's documented OAuth callback for hosted Claude surfaces - not environment-specific.
MCP_REDIRECT_URI = "https://claude.ai/api/mcp/auth_callback"


def pkce_required(client_id: str) -> bool:
    """Per-client PKCE policy for OAUTH2_PROVIDER["PKCE_REQUIRED"] - preserves the first-party
    client's existing no-PKCE behavior (see settings.py's comment on why) while requiring it
    strictly for the MCP client, since Claude always sends PKCE regardless of registration
    mechanism (confirmed in Anthropic's docs)."""
    return client_id == MCP_CLIENT_ID
