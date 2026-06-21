package com.cadence.api.security.oauth;

import com.cadence.api.common.config.CadenceProperties;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;

/**
 * There is exactly one OAuth2 client in this system - Cadence's own first-party app -
 * and the contract has no endpoint for registering others, so an in-memory repository
 * (rather than a database table devoted to a single never-changing row) is the simplest
 * correct fit.
 */
@Configuration
public class FirstPartyClientConfig {

	public static final String CLIENT_ID = "cadence-first-party";

	@Bean
	public RegisteredClientRepository registeredClientRepository(PasswordEncoder passwordEncoder, CadenceProperties properties) {
		String redirectUri = properties.cors().allowedOrigins().isEmpty()
				? "http://localhost:5173/oauth/callback"
				: properties.cors().allowedOrigins().get(0) + "/oauth/callback";

		RegisteredClient client = RegisteredClient.withId(CLIENT_ID)
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

		return new InMemoryRegisteredClientRepository(client);
	}
}
