package com.cadence.api.security;

import com.cadence.api.security.pat.PersonalAccessTokenAuthenticationProvider;
import com.cadence.api.security.pat.PersonalAccessTokenService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationManagerResolver;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;
import org.springframework.security.oauth2.server.resource.authentication.OpaqueTokenAuthenticationProvider;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;
import org.springframework.stereotype.Component;

/**
 * Three bearer-credential shapes (OAuth2 opaque token, RS256 JWT, personal access
 * token) coexist on the same endpoints. Spring Security's documented extension point
 * for that is {@link AuthenticationManagerResolver} - inspect the token's shape once,
 * route to exactly one purpose-built {@link AuthenticationManager}, rather than trying
 * all three and seeing which one doesn't throw.
 */
@Component
public class BearerSchemeAuthenticationManagerResolver implements AuthenticationManagerResolver<HttpServletRequest> {

	private final AuthenticationManager jwtManager;
	private final AuthenticationManager oauth2Manager;
	private final AuthenticationManager patManager;
	private final PersonalAccessTokenService patService;

	public BearerSchemeAuthenticationManagerResolver(JwtDecoder jwtDecoder, OpaqueTokenIntrospector introspector,
			PersonalAccessTokenAuthenticationProvider patProvider, PersonalAccessTokenService patService) {
		this.jwtManager = new ProviderManager(new JwtAuthenticationProvider(jwtDecoder));
		this.oauth2Manager = new ProviderManager(new OpaqueTokenAuthenticationProvider(introspector));
		this.patManager = patProvider::authenticate;
		this.patService = patService;
	}

	@Override
	public AuthenticationManager resolve(HttpServletRequest request) {
		String token = extractBearerToken(request);
		if (token == null) {
			return authentication -> {
				throw new InvalidBearerTokenException("Missing bearer token.");
			};
		}
		if (patService.looksLikePersonalAccessToken(token)) {
			return patManager;
		}
		if (looksLikeJwt(token)) {
			return jwtManager;
		}
		return oauth2Manager;
	}

	private boolean looksLikeJwt(String token) {
		return token.chars().filter(c -> c == '.').count() == 2;
	}

	private String extractBearerToken(HttpServletRequest request) {
		String header = request.getHeader(HttpHeaders.AUTHORIZATION);
		if (header == null || header.length() < 8 || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
			return null;
		}
		String value = header.substring(7).trim();
		return value.isEmpty() ? null : value;
	}
}
