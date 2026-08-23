package com.cadence.api.security.oauth;

import com.cadence.api.common.config.CadenceProperties;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;

/**
 * Cadence's own first-party app client - trusted, so it skips PKCE and the consent screen
 * (the frontend's login IS the consent, effectively). See {@link McpClientConfig} for the
 * second, less-trusted client, and {@link OAuthClientRepositoryConfig} for how both compose
 * into the one {@link RegisteredClientRepository} bean.
 */
@Configuration
public class FirstPartyClientConfig {

	public static final String CLIENT_ID = "cadence-first-party";

	@Bean
	public RegisteredClient cadenceFirstPartyClient(PasswordEncoder passwordEncoder, CadenceProperties properties) {
		String redirectUri = properties.cors().allowedOrigins().isEmpty()
				? "http://localhost:5173/oauth/callback"
				: properties.cors().allowedOrigins().get(0) + "/oauth/callback";

		return RegisteredClient.withId(CLIENT_ID)
				.clientId(CLIENT_ID)
				.clientSecret(passwordEncoder.encode(properties.oauth().firstPartyClientSecret()))
				.clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
				.clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
				.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
				.authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
				.redirectUri(redirectUri)
				.scope("activities:read")
				.scope("activities:write")
				.scope("workouts:write")
				.scope("calendar:write")
				.scope("coach")
				.scope("gear:write")
				.clientSettings(ClientSettings.builder()
						.requireProofKey(false)
						.requireAuthorizationConsent(false)
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
