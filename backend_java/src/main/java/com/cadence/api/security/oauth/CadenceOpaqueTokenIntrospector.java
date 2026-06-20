package com.cadence.api.security.oauth;

import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.resource.introspection.BadOpaqueTokenException;
import org.springframework.security.oauth2.server.resource.introspection.OAuth2IntrospectionAuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;
import org.springframework.stereotype.Component;

/**
 * Validates {@code cad_at_...} access tokens for the resource server. Spring
 * Authorization Server is the same application as the resource server here, so this is
 * a direct {@link OAuth2AuthorizationService} lookup rather than an HTTP introspection
 * round-trip - the pattern the framework itself recommends for monolith deployments.
 */
@Component
public class CadenceOpaqueTokenIntrospector implements OpaqueTokenIntrospector {

	private final OAuth2AuthorizationService authorizationService;
	private final UserRepository userRepository;

	public CadenceOpaqueTokenIntrospector(OAuth2AuthorizationService authorizationService, UserRepository userRepository) {
		this.authorizationService = authorizationService;
		this.userRepository = userRepository;
	}

	@Override
	public OAuth2AuthenticatedPrincipal introspect(String token) {
		OAuth2Authorization authorization = authorizationService.findByToken(token, OAuth2TokenType.ACCESS_TOKEN);
		if (authorization == null) {
			throw new BadOpaqueTokenException("Invalid access token.");
		}
		OAuth2Authorization.Token<OAuth2AccessToken> accessToken = authorization.getAccessToken();
		if (accessToken == null || !accessToken.isActive()) {
			throw new BadOpaqueTokenException("Access token is expired or revoked.");
		}
		User user = userRepository.findByEmailIgnoreCase(authorization.getPrincipalName())
				.orElseThrow(() -> new BadOpaqueTokenException("Unknown principal."));

		Map<String, Object> attributes = new HashMap<>();
		attributes.put("sub", user.getId());
		attributes.put("scope", List.copyOf(accessToken.getToken().getScopes()));
		return new OAuth2IntrospectionAuthenticatedPrincipal(user.getId(), attributes, List.of());
	}
}
