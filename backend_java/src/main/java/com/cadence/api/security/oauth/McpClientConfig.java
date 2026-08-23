package com.cadence.api.security.oauth;

import com.cadence.api.common.config.CadenceProperties;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;

/**
 * The OAuth client for the MCP server, used by Claude.ai/Claude Desktop's remote-connector
 * flow - a genuine third-party grant, unlike {@link FirstPartyClientConfig}'s trusted first-party
 * client, so this one requires PKCE and a real consent screen. The redirect URI is Anthropic's
 * documented constant for hosted Claude surfaces (web/Desktop/mobile/Cowork), not derived from
 * CORS config like the first-party client's - Claude is never same-origin with this API.
 *
 * <p>No Dynamic Client Registration endpoint exists or is needed: per Anthropic's own docs
 * (claude.com/docs/connectors/building/authentication), a user adding this as a custom connector
 * pastes this client's id/secret into Claude's "Add custom connector -&gt; Advanced settings"
 * alongside the server URL - a fully self-service path with no registration_endpoint required.
 *
 * <p>{@code coach} is deliberately excluded from this client's scopes for now - whether an AI
 * assistant should be able to act on a coach's behalf against a shared athlete's data is a real
 * product question, left for a later phase.
 */
@Configuration
public class McpClientConfig {

	public static final String CLIENT_ID = "cadence-mcp";

	/** Claude's documented OAuth callback for hosted surfaces - not environment-specific. */
	private static final String CLAUDE_REDIRECT_URI = "https://claude.ai/api/mcp/auth_callback";

	@Bean
	public RegisteredClient cadenceMcpClient(PasswordEncoder passwordEncoder, CadenceProperties properties) {
		return RegisteredClient.withId(CLIENT_ID)
				.clientId(CLIENT_ID)
				.clientSecret(passwordEncoder.encode(properties.oauth().mcpClientSecret()))
				.clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
				.clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
				.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
				.authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
				.redirectUri(CLAUDE_REDIRECT_URI)
				.scope("activities:read")
				.scope("activities:write")
				.scope("workouts:write")
				.scope("calendar:write")
				.scope("gear:write")
				// Not a real Cadence permission - Claude auto-appends this to the authorization
				// request when the AS metadata advertises it in scopes_supported (see
				// SecurityConfig's protectedResourceMetadata customizer) to obtain a refresh
				// token. Found live: without registering it here too, Spring Authorization
				// Server rejects the whole request as invalid_scope before login is even reached.
				.scope("offline_access")
				.clientSettings(ClientSettings.builder()
						.requireProofKey(true)
						.requireAuthorizationConsent(true)
						.build())
				.tokenSettings(TokenSettings.builder()
						.accessTokenFormat(OAuth2TokenFormat.REFERENCE)
						.accessTokenTimeToLive(Duration.ofSeconds(21600))
						.refreshTokenTimeToLive(Duration.ofDays(30))
						.reuseRefreshTokens(false)
						.build())
				.build();
	}
}
