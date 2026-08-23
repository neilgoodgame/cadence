# MCP server: OAuth authorization

How the Cadence API authenticates a Model Context Protocol client (Claude.ai / Claude Desktop's
remote-connector flow) against `POST /mcp`. This is the authorization *foundation* only - the
actual MCP tools (`list_activities`, `create_workout`, etc., under `mcp/tools/`) have no separate
doc file of their own; see their own Javadoc and `McpToolsIntegrationTest.java` for what each one
does. This file covers exclusively how a client proves who it is before any tool call is allowed.

See `ROADMAP.md`'s "Model Context Protocol server" entry for why this exists at all.

## The two OAuth clients

Cadence's Spring Authorization Server (`security/oauth/AuthorizationServerConfig.java`) now
registers two clients, composed into one `RegisteredClientRepository`
(`security/oauth/OAuthClientRepositoryConfig.java`):

| | `cadence-first-party` | `cadence-mcp` |
|---|---|---|
| Config | `FirstPartyClientConfig.java` | `McpClientConfig.java` |
| Used by | Cadence's own React frontend | Claude.ai / Claude Desktop |
| PKCE required | No | **Yes** |
| Consent screen | No (login *is* the consent) | **Yes** |
| Redirect URI | Derived from `cadence.cors.allowed-origins` | `https://claude.ai/api/mcp/auth_callback` (Anthropic's documented constant for all hosted Claude surfaces) |
| Scopes | All 6 (incl. `coach`) | 5 real scopes + `offline_access`, `coach` excluded (see below) |

The first-party client is trusted (it's Cadence's own code) so it skips PKCE and consent, matching
the general OAuth principle that a *confidential, first-party* client doesn't need the same
protections a *public, third-party* client does. `cadence-mcp` is a genuine third-party grant, so
it gets both.

`coach` is deliberately excluded from `cadence-mcp`'s scopes for now - whether an AI assistant
should be able to act on a coach's behalf against a shared athlete's data is a real product
question, left for a later phase.

## Why no Dynamic Client Registration

MCP's authorization spec supports OAuth 2.0 Dynamic Client Registration
([RFC 7591](https://www.rfc-editor.org/rfc/rfc7591)) so a client can self-register instead of an
operator hand-creating a `RegisteredClient`. Cadence doesn't implement it, and per Anthropic's own
docs this is a supported, recommended path for a **custom connector** (as opposed to a
directory-listed one): a user adds the connector by URL and pastes a pre-registered
`client_id`/`client_secret` into "Advanced settings" themselves. See
[Authentication for connectors](https://claude.com/docs/connectors/building/authentication)
("Supplying your own pre-registered client ID... is a good option when you want a stable OAuth
client per organization: it avoids dynamic client registration entirely").

## Discovery: how Claude finds this server's authorization server

This is the part that's easy to get wrong by assuming a client just fetches a well-known URL. Per
Anthropic's docs, the *primary* mechanism is a `401` challenge:

1. Claude sends a request to `/mcp` with no token (or a stale one).
2. The server must respond `401` with a `WWW-Authenticate` header pointing at its protected-resource
   metadata document:
   ```
   WWW-Authenticate: Bearer resource_metadata="https://api.cadence.cc/.well-known/oauth-protected-resource"
   ```
   Implemented as `McpAuthenticationEntryPoint` (`mcp/McpAuthenticationEntryPoint.java`), wired
   into `SecurityConfig`'s `securityEntryPoint` bean - a `DelegatingAuthenticationEntryPoint` that
   routes only `/mcp` to this entry point, leaving every other endpoint's 401 behavior unchanged.
   Claude only falls back to *probing* `.well-known` paths directly if this header is absent.
3. Claude fetches that document -
   [RFC 9728](https://www.rfc-editor.org/rfc/rfc9728) Protected Resource Metadata - which lists
   `authorization_servers`. Cadence uses **Spring Security's own built-in RFC 9728 filter**
   (`OAuth2ResourceServerConfigurer.protectedResourceMetadata(...)` in `SecurityConfig`), not a
   hand-written controller - that was the first thing tried, and it turned out Spring Security
   6.x already ships this, which took priority over a hand-rolled version and was adopted
   instead.
4. Claude fetches the authorization server's own metadata -
   [RFC 8414](https://www.rfc-editor.org/rfc/rfc8414) Authorization Server Metadata - at
   `/.well-known/oauth-authorization-server`. Spring Authorization Server auto-registers this
   endpoint as part of its own endpoint set, but that set is `authenticated()`-only by default in
   this app's config; `AuthorizationServerConfig` carves out an explicit `permitAll()` for just
   this path, since RFC 8414 requires the document to be publicly fetchable.

Both metadata endpoints must respond well under Claude's 10-second discovery timeout - see
"Endpoint latency" in Anthropic's docs linked above.

## The authorization flow

Standard [OAuth 2.0 authorization code grant](https://www.rfc-editor.org/rfc/rfc6749#section-4.1)
with [PKCE](https://www.rfc-editor.org/rfc/rfc7636) (S256 only - Cadence's authorization server
metadata advertises `code_challenge_methods_supported: ["S256"]`, and Claude always sends PKCE
regardless of registration mechanism per its docs). This exact sequence has been run end-to-end
against local dev with real HTTP requests, not just assumed from the framework:

1. **`GET /oauth/authorize`** with `client_id=cadence-mcp`, the requested scopes, `code_challenge`,
   `code_challenge_method=S256`, `state`. Unauthenticated → `302` to `/login`.
2. **Login** (Spring Security's default form-login page, `POST /login`) → on success, redirects
   back to the saved `/oauth/authorize` request (Spring Security's `HttpSessionRequestCache`).
3. **Consent screen**: since `cadence-mcp` has `requireAuthorizationConsent(true)`, the now-
   authenticated request to `/oauth/authorize` renders Spring Authorization Server's built-in
   consent page - a plain HTML form listing every requested scope as a checkbox, POSTing back to
   `/oauth/authorize` with the approved `scope` values plus a server-issued consent `state` token
   (distinct from the original OAuth `state` param - it correlates the consent submission back to
   the pending authorization request). No custom consent UI was built; the default page is
   functional and was judged good enough for v1 (see "Not done yet" below for the caveat).
4. **Consent approval** → `302` to the registered redirect URI
   (`https://claude.ai/api/mcp/auth_callback`) with an authorization `code` and the *original*
   `state`, confirmed round-tripped correctly.
5. **`POST /oauth/token`**, `client_secret_basic` auth (or `_post`), `grant_type=authorization_code`,
   the `code`, `redirect_uri`, and PKCE `code_verifier`. Returns a `cad_at_.../cad_rt_...` token
   pair (opaque, not JWT - see the main `README.md`'s "Auth & delegation" section) with the
   granted scopes.
6. That access token is a normal bearer token from here on - `POST /mcp` with
   `Authorization: Bearer cad_at_...` works exactly like every other authenticated endpoint,
   through the same `BearerSchemeAuthenticationManagerResolver` + `AuthContextFilter` REST
   controllers use.

### `offline_access`

Claude auto-appends `offline_access` to the requested scope whenever a server's protected-resource
metadata lists it in `scopes_supported`, specifically to obtain a refresh token (see "DCR and CIMD
details" in Anthropic's docs). It isn't a real Cadence permission - it's registered on
`cadence-mcp` (`McpClientConfig`) purely so Spring Authorization Server doesn't reject it as
`invalid_scope`. **A real bug found via the live end-to-end flow test**: it was initially only
listed in the protected-resource metadata's advertised `scopes_supported`, not on the client's own
registered scopes - Spring AS rejected the whole authorization request outright before the user
ever reached the login page. Fixed, and covered by `OAuthClientConfigTest.mcpClientRequiresPkceAndConsent`'s
`offline_access` assertion so it can't silently regress.

## Scope enforcement

The six existing scope strings (`activities:read`, `activities:write`, `workouts:write`,
`calendar:write`, `gear:write`, `coach`) were never enforced per-REST-endpoint anywhere in this
app - authorization is athlete-ownership-based (`AccessGuard`/`PermissionService`). MCP tools add
one new check on top: `mcp/dispatch/McpToolAuthorizer.requireScope(...)`, called at the start of
each tool method, checking the *token's* granted scopes (from `AuthContextHolder`) before the
tool's underlying service call runs. `activities:read` is reused as the umbrella "read my training
data" scope for every read tool (no `workouts:read`/`calendar:read`/`gear:read` exist) - a product
choice, not a technical constraint.

## Where this lives

| Concern | File |
|---|---|
| First-party client | `security/oauth/FirstPartyClientConfig.java` |
| MCP client | `security/oauth/McpClientConfig.java` |
| Client repository composition | `security/oauth/OAuthClientRepositoryConfig.java` |
| AS metadata + issuer | `security/oauth/AuthorizationServerConfig.java` |
| `/mcp` auth requirement + discovery entry point | `security/SecurityConfig.java` |
| The `/mcp`-specific 401 + `WWW-Authenticate` | `mcp/McpAuthenticationEntryPoint.java` |
| Per-tool scope check | `mcp/dispatch/McpToolAuthorizer.java`, `McpScopes.java` |
| `cadence.oauth.issuer`/`mcp-client-secret` config | `application.yml`, `.env.example`, `CadenceProperties.java` |

## Testing

**Automated** (`security/oauth/OAuthClientConfigTest.java`, `mcp/McpSecurityTest.java`):
- First-party client still has no PKCE/consent (regression guard).
- MCP client requires both, has the correct redirect URI, excludes `coach`, includes `offline_access`.
- Both `.well-known/` endpoints return `200` unauthenticated with the right shape.
- An unauthenticated `/mcp` request gets the `WWW-Authenticate` discovery header; every other
  endpoint's 401 doesn't.

**Manual, live** (reproducible against local dev - `docker compose up -d` from `backend_java/`):
ran the full flow above with `curl` and a cookie jar - generate a PKCE verifier/challenge, hit
`/oauth/authorize`, log in, parse and submit the real consent form, exchange the code with the
verifier, and call a real tool with the resulting token. This is what actually caught the
`offline_access` bug above; the automated tests alone wouldn't have (they check configuration
shape, not the live multi-step protocol interaction).

## Not done yet

- **No real Claude.ai/Claude Desktop connection has been made.** Every step above was verified by
  scripting the exact same HTTP requests Claude's client would make, not by adding a real custom
  connector - that requires a publicly reachable server (staging), not local dev.
- **CloudFront's behavior with Streamable HTTP is unverified.** CloudFront is known to forward
  this app's existing hand-rolled `SseEmitter` responses correctly (used by other recompute
  endpoints), a reasonable signal but not a guarantee for the MCP SDK's own Streamable HTTP
  framing - needs checking once deployed.
- **The consent screen is Spring Authorization Server's unstyled default.** Functional, and
  sufficient for "the user sees and approves what's being granted," but not branded. A custom
  page would need a `.consentPage(...)` controller and a templating engine (none is currently a
  dependency).

## External documentation

- [RFC 6749](https://www.rfc-editor.org/rfc/rfc6749) - OAuth 2.0 Authorization Framework (the base spec)
- [RFC 7636](https://www.rfc-editor.org/rfc/rfc7636) - PKCE
- [RFC 7591](https://www.rfc-editor.org/rfc/rfc7591) - Dynamic Client Registration (not implemented; see above)
- [RFC 8414](https://www.rfc-editor.org/rfc/rfc8414) - Authorization Server Metadata
- [RFC 9728](https://www.rfc-editor.org/rfc/rfc9728) - Protected Resource Metadata
- [MCP Authorization specification](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization) (the 2025-11-25 revision, current as of this work)
- [Anthropic: Authentication for connectors](https://claude.com/docs/connectors/building/authentication) - the authoritative source for everything Claude-client-specific in this doc (callback URLs, DCR/CIMD, `offline_access` behavior, discovery, endpoint latency)
- [Spring Authorization Server reference documentation](https://docs.spring.io/spring-authorization-server/reference/index.html)
