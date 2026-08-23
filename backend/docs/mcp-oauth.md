# MCP server: OAuth authorization

How the Django backend authenticates a Model Context Protocol client (Claude.ai / Claude
Desktop's remote-connector flow) against `POST /mcp`. This is the authorization *foundation*
only - the actual MCP tools don't exist yet on this backend (they do on the Java backend's
follow-up PR); this file covers exclusively how a client proves who it is before any tool call
would be allowed.

This is the Django equivalent of the Java backend's `backend_java/docs/mcp-oauth.md` (that PR
merged first) - kept at deliberate feature parity, since production may move to this backend
later. Where the two backends' implementations genuinely differ (mostly: django-oauth-toolkit has
no built-in RFC 8414/9728 support, and no session-login page existed here at all before this
work), that's called out explicitly below rather than assumed identical.

## The two OAuth clients

`django-oauth-toolkit`'s `Application` model now has two rows:

| | `cadence-first-party` | `cadence-mcp` |
|---|---|---|
| Created by | `authn/oauth_utils.py` (lazily, `get_or_create` on first use) | `authn/migrations/0001_seed_mcp_oauth_application.py` (eagerly, at migrate time) |
| Grant type | Resource Owner Password Credentials | Authorization code |
| Used by | Cadence's own React frontend | Claude.ai / Claude Desktop |
| PKCE required | No | **Yes** |
| Consent screen (`skip_authorization`) | N/A - password grant never reaches `/oauth/authorize/` | **Required** (`False`) |
| Redirect URI | N/A (password grant has none) | `https://claude.ai/api/mcp/auth_callback` (Anthropic's documented constant for all hosted Claude surfaces) |
| Scopes | All 6 (incl. `coach`) | 5 real scopes + `offline_access`, `coach` excluded by convention (see below) |

The first-party client mints tokens directly (`issue_token_pair()`), bypassing `/oauth/authorize/`
and consent entirely - there's no frontend redirect dance for a first-party client the API already
trusts. `cadence-mcp` is a genuine third-party grant, so it gets the full authorization-code +
PKCE + consent treatment.

**Why a data migration, not lazy `get_or_create` like the first-party client**: nothing in
oauth2-toolkit's stock `AuthorizationView`/`TokenView` calls back into project code to create an
`Application` on demand - they look rows up directly from the database by `client_id`. The
first-party client's lazy pattern only works because something in the real request path
(`issue_token_pair()`, called from login/registration) happens to invoke it. `cadence-mcp` has no
such call site, so the row has to exist before the first real request - a migration is the
idiomatic Django answer for "this one row must exist, once, in dependency order."

`coach` is deliberately excluded from what `cadence-mcp` requests/is granted for now - whether an
AI assistant should be able to act on a coach's behalf against a shared athlete's data is a real
product question, left for a later phase. Unlike Spring Authorization Server (each
`RegisteredClient` lists its own allowed scopes), django-oauth-toolkit has **no per-Application
scope allowlist** without an add-on - so this isn't enforced at the `Application` level here, only
by what's actually requested/granted at authorize time.

## Why no Dynamic Client Registration

Same reasoning as the Java backend: MCP's authorization spec supports OAuth 2.0 Dynamic Client
Registration ([RFC 7591](https://www.rfc-editor.org/rfc/rfc7591)), but Cadence doesn't implement
it. Per Anthropic's own docs, a user adding this as a **custom connector** pastes a pre-registered
`client_id`/`client_secret` into "Advanced settings" themselves - a fully self-service path with no
`registration_endpoint` required. See
[Authentication for connectors](https://claude.com/docs/connectors/building/authentication).

## Discovery: how Claude finds this server's authorization server

Per Anthropic's docs, the *primary* mechanism is a `401` challenge, not a passive `.well-known`
fetch:

1. Claude sends a request to `/mcp` with no token (or a stale one).
2. The server must respond `401` with a `WWW-Authenticate` header pointing at its
   protected-resource metadata document:
   ```
   WWW-Authenticate: Bearer resource_metadata="https://api.cadence.cc/.well-known/oauth-protected-resource"
   ```
   Implemented as `McpOAuth2Authentication` (`authn/mcp_auth.py`), a thin `OAuth2Authentication`
   subclass overriding `authenticate_header()`, wired in only via
   `DJANGO_MCP_AUTHENTICATION_CLASSES` - `django-mcp-server` applies that setting only to its own
   `/mcp` view (`mcp_server/urls.py`), not the global `REST_FRAMEWORK` auth chain, so every other
   endpoint's `401` (`Bearer realm="api"`) is unaffected. Direct equivalent of the Java backend's
   `McpAuthenticationEntryPoint`, just expressed as a DRF authentication-class override instead of
   a Spring Security entry point, since Django/DRF has no equivalent hook.
3. Claude fetches that document -
   [RFC 9728](https://www.rfc-editor.org/rfc/rfc9728) Protected Resource Metadata. **Unlike the
   Java backend, where Spring Security ships a real built-in RFC 9728 filter**,
   django-oauth-toolkit has no built-in support for this at all (confirmed via grep: no
   `oauth2_provider.urls.oidc` import, no `OIDC_*` settings anywhere in this project) - it's a
   hand-written view, `authn.discovery_views.oauth_protected_resource_metadata`.
4. Claude fetches the authorization server's own metadata -
   [RFC 8414](https://www.rfc-editor.org/rfc/rfc8414) Authorization Server Metadata - at
   `/.well-known/oauth-authorization-server`. Same story: also hand-written
   (`authn.discovery_views.oauth_authorization_server_metadata`), also no library support to lean
   on. Both views are `AllowAny` (unauthenticated `authentication_classes = []`), since both specs
   require the documents to be publicly fetchable.

## The authorization flow

Standard [OAuth 2.0 authorization code grant](https://www.rfc-editor.org/rfc/rfc6749#section-4.1)
with [PKCE](https://www.rfc-editor.org/rfc/rfc7636) (S256 only - this server's authorization
server metadata advertises `code_challenge_methods_supported: ["S256"]`, and Claude always sends
PKCE regardless of registration mechanism per its docs). This exact sequence has been run
end-to-end against local dev with real HTTP requests (`requests` + a session cookie jar), not just
assumed from the framework:

1. **`GET /oauth/authorize/`** with `client_id=cadence-mcp`, the requested scopes,
   `code_challenge`, `code_challenge_method=S256`, `state`. Unauthenticated → `302` to
   `/oauth/login/?next=...`.
2. **Login** - `authn.login_views.MCPLoginView`, a thin subclass of Django's stock
   `django.contrib.auth.views.LoginView` rendering `authn/templates/authn/login.html`. **This page
   didn't exist at all before this work** - Django's `LOGIN_URL` was unset, and the existing
   `/v1/auth/login` (`accounts/views.py`) is a pure JSON DRF `APIView` that never touches Django's
   session, so it couldn't satisfy `AuthorizationView`'s `LoginRequiredMixin`. Uses Django's stock
   `AuthenticationForm`, which works against `accounts.User` out of the box (`USERNAME_FIELD =
   "email"`, `set_password()`/Django's standard hashing - no custom auth backend needed). On
   success, redirects back to the saved `next` (the original `/oauth/authorize/` request).
3. **Consent screen**: since `cadence-mcp` has `skip_authorization=False`, the now-authenticated
   request to `/oauth/authorize/` renders oauth2-toolkit's built-in consent page
   (`oauth2_provider/authorize.html`, which - checked directly - extends its own fully
   self-contained `oauth2_provider/base.html`, no dependency on any template this project
   provides) - a plain HTML form with the requested scope as a single hidden field, POSTing back
   to `/oauth/authorize/` with an `allow`/deny submit button. No custom consent UI was built; the
   default page is functional and judged good enough for v1 (see "Not done yet" below).
4. **Consent approval** → `302` to the registered redirect URI
   (`https://claude.ai/api/mcp/auth_callback`) with an authorization `code` and the *original*
   `state`, confirmed round-tripped correctly.
5. **`POST /oauth/token`**, `client_id`/`client_secret` in the body, `grant_type=authorization_code`,
   the `code`, `redirect_uri`, and PKCE `code_verifier`. Returns a `cad_at_.../cad_rt_...` token
   pair (opaque, matching the first-party client's existing token format) with the granted scopes.
6. That access token is a normal bearer token from here on - `POST /mcp` with
   `Authorization: Bearer cad_at_...` works exactly like every other authenticated endpoint, via
   the same `OAuth2Authentication` class (subclassed as `McpOAuth2Authentication` only for its
   401 header, not its token-validation logic) already in
   `REST_FRAMEWORK["DEFAULT_AUTHENTICATION_CLASSES"]`.

### `offline_access`

Claude auto-appends `offline_access` to the requested scope whenever a server's protected-resource
metadata lists it in `scopes_supported`, specifically to obtain a refresh token. It isn't a real
Cadence permission - it's registered in the global `OAUTH2_PROVIDER["SCOPES"]` dict purely so
oauth2-toolkit doesn't reject it as `invalid_scope`. Unlike the Java backend (where scopes are
per-`RegisteredClient` and `offline_access` had to be registered specifically on `cadence-mcp` -
a real bug was found and fixed there when it was initially missing), django-oauth-toolkit's
`SCOPES` setting is global across all clients, so there's no equivalent per-client step to miss
here.

## PKCE: per-client, not global

`openapi.yaml`'s documented `/oauth/token` contract has no `code_challenge`/`code_verifier`
fields, so PKCE can't be required globally without breaking the first-party client's documented
contract (the toolkit defaults `PKCE_REQUIRED` to `True`). `PKCE_REQUIRED` accepts a bool *or* a
callable taking a client id and returning a bool (confirmed against the current
[django-oauth-toolkit settings reference](https://django-oauth-toolkit.readthedocs.io/en/latest/settings.html)) -
`authn.mcp_oauth.pkce_required` returns `True` only for `cadence-mcp`, preserving the first-party
client's exact prior behavior. Imported eagerly as a real function object in `settings.py` (not a
dotted-path string): confirmed directly against the installed library's source that `PKCE_REQUIRED`
isn't in its `IMPORT_STRINGS` list the way `ACCESS_TOKEN_GENERATOR`/`REFRESH_TOKEN_GENERATOR` are -
`oauth2_validators.py` does a plain `callable(oauth2_settings.PKCE_REQUIRED)` check against the raw
settings value, so it must already be a callable object, not a string to resolve.

## Scope enforcement

The six existing scope strings were never enforced per-DRF-view anywhere in this app -
authorization is athlete-ownership-based (`core/permissions.py`'s `IsAuthorizedForAthleteRead`/
`Write` + `UserRelationship`), identical finding to the Java backend. No MCP tools exist on this
backend yet to add a scope check on top of that.

## Where this lives

| Concern | File |
|---|---|
| First-party client | `authn/oauth_utils.py` (unmodified) |
| MCP client constants/PKCE policy | `authn/mcp_oauth.py` |
| MCP client row (eager creation) | `authn/migrations/0001_seed_mcp_oauth_application.py` |
| Session login page | `authn/login_views.py`, `authn/templates/authn/login.html` |
| Discovery endpoints (RFC 8414 + 9728) | `authn/discovery_views.py` |
| The `/mcp`-specific 401 + `WWW-Authenticate` | `authn/mcp_auth.py` |
| `/mcp` mount | `config/urls.py`, `config/settings.py` (`INSTALLED_APPS`, `DJANGO_MCP_AUTHENTICATION_CLASSES`) |
| `OAUTH_MCP_CLIENT_SECRET`/`OAUTH_ISSUER` config | `config/settings.py` |

## Testing

**Automated** (`authn/tests.py` - `McpOAuthClientTests`, `DiscoveryEndpointTests`,
`McpAuthChallengeTests`):
- First-party client still has no PKCE requirement (regression guard).
- `cadence-mcp` requires PKCE and consent, has the correct redirect URI.
- `offline_access` is registered globally.
- Both `.well-known/` endpoints return `200` unauthenticated with the expected shape.
- An unauthenticated `/mcp` request gets the `WWW-Authenticate` discovery header; every other
  endpoint's `401` doesn't.

**Manual, live** (reproducible against local dev - `docker compose up -d --build backend`): ran
the full flow above with Python's `requests` and a session cookie jar - generate a PKCE
verifier/challenge, hit `/oauth/authorize/`, log in via the new session login page, parse and
submit the real consent form, exchange the code with the verifier, and call a real `initialize`
request against `/mcp` with the resulting token. Confirmed the granted scope, the round-tripped
`state`, and a working (non-401) `/mcp` response with `django_mcp_server`'s own `serverInfo` in
the reply.

## Not done yet

- **No real Claude.ai/Claude Desktop connection has been made.** Every step above was verified by
  scripting the exact same HTTP requests Claude's client would make, not by adding a real custom
  connector - that requires a publicly reachable server (staging), not local dev.
- **`django-mcp-server`'s Streamable HTTP behavior under multi-worker gunicorn** (`--workers 3` in
  production) is unverified beyond a single-worker local dev container - worth a live check once
  deployed, same caveat the Java backend's CloudFront/Streamable-HTTP note carries.
- **The consent screen is oauth2-toolkit's unstyled default.** Functional, sufficient for "the
  user sees and approves what's being granted," but not branded.
- **The login page is minimal, unbranded HTML** (`authn/templates/authn/login.html`) - functional,
  not styled to match the product.
- **No MCP tools exist on this backend at all.** This PR only proves `/mcp` is mounted, secured,
  and returns a real MCP `initialize` response once authenticated - mirroring how the Java
  backend's authorization PR verified its transport before any tools existed. Django tools (if
  this backend is ever promoted to production) are unstarted, unlike Java's, which already has a
  full read/write tool set pending its own follow-up PR.

## External documentation

- [RFC 6749](https://www.rfc-editor.org/rfc/rfc6749) - OAuth 2.0 Authorization Framework (the base spec)
- [RFC 7636](https://www.rfc-editor.org/rfc/rfc7636) - PKCE
- [RFC 7591](https://www.rfc-editor.org/rfc/rfc7591) - Dynamic Client Registration (not implemented; see above)
- [RFC 8414](https://www.rfc-editor.org/rfc/rfc8414) - Authorization Server Metadata
- [RFC 9728](https://www.rfc-editor.org/rfc/rfc9728) - Protected Resource Metadata
- [MCP Authorization specification](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization) (the 2025-11-25 revision, current as of this work)
- [Anthropic: Authentication for connectors](https://claude.com/docs/connectors/building/authentication) - the authoritative source for everything Claude-client-specific in this doc (callback URLs, DCR/CIMD, `offline_access` behavior, discovery, endpoint latency)
- [django-oauth-toolkit settings reference](https://django-oauth-toolkit.readthedocs.io/en/latest/settings.html) - `PKCE_REQUIRED`'s callable form
- [django-mcp-server](https://github.com/omarbenhamid/django-mcp-server) - the WSGI-compatible Django MCP transport `/mcp` is mounted with
