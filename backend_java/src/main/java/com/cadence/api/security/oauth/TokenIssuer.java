package com.cadence.api.security.oauth;

import com.cadence.api.users.User;
import java.security.Principal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.stereotype.Component;

/**
 * Issues an OAuth2 token pair for a given user, bypassing the authorization-code redirect
 * dance entirely - used by both registration (a brand-new user) and {@code /v1/auth/login}
 * (an existing one re-authenticating with email+password), neither of which has a browser
 * flow to perform. Builds a fully-formed {@link OAuth2Authorization} directly (a supported
 * usage of {@link OAuth2AuthorizationService}) and reuses {@link CadenceTokenGenerator}'s
 * token format so these paths and the real {@code /oauth/token} endpoint can never drift.
 */
@Component
public class TokenIssuer {

	public static final Set<String> ALL_SCOPES = Set.of(
			"activities:read", "activities:write", "workouts:write", "calendar:write", "coach", "gear:write");

	private static final Duration ACCESS_TOKEN_TTL = Duration.ofSeconds(21600);
	private static final Duration REFRESH_TOKEN_TTL = Duration.ofDays(30);

	private final RegisteredClientRepository registeredClientRepository;
	private final OAuth2AuthorizationService authorizationService;

	public TokenIssuer(RegisteredClientRepository registeredClientRepository,
			OAuth2AuthorizationService authorizationService) {
		this.registeredClientRepository = registeredClientRepository;
		this.authorizationService = authorizationService;
	}

	public record TokenPair(String accessToken, String refreshToken, int expiresIn, String scope) {
	}

	public TokenPair issueTokenPair(User user) {
		RegisteredClient client = registeredClientRepository.findByClientId(FirstPartyClientConfig.CLIENT_ID);
		Instant now = Instant.now();

		OAuth2AccessToken accessToken = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER,
				CadenceTokenGenerator.randomToken("cad_at_"), now, now.plus(ACCESS_TOKEN_TTL), ALL_SCOPES);
		OAuth2RefreshToken refreshToken = new OAuth2RefreshToken(
				CadenceTokenGenerator.randomToken("cad_rt_"), now, now.plus(REFRESH_TOKEN_TTL));

		// The refresh_token grant requires the authenticated principal to be stored as an
		// attribute (OAuth2RefreshTokenAuthenticationProvider reads it to mint the new pair);
		// principalName alone is not enough - without this, every refresh of a login-issued
		// token fails with "principal cannot be null".
		Authentication principal = UsernamePasswordAuthenticationToken.authenticated(user.getEmail(), null, List.of());

		OAuth2Authorization authorization = OAuth2Authorization.withRegisteredClient(client)
				.id(UUID.randomUUID().toString())
				.principalName(user.getEmail())
				.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
				.authorizedScopes(ALL_SCOPES)
				.attribute(Principal.class.getName(), principal)
				.token(accessToken)
				.token(refreshToken)
				.build();
		authorizationService.save(authorization);

		return new TokenPair(accessToken.getTokenValue(), refreshToken.getTokenValue(),
				(int) ACCESS_TOKEN_TTL.toSeconds(), String.join(" ", ALL_SCOPES));
	}
}
