package com.cadence.api.security;

import com.cadence.api.security.pat.PersonalAccessTokenAuthentication;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Bridges whichever of the three bearer-credential schemes authenticated this request
 * into one consistent {@link AuthContext}, regardless of which {@code AuthenticationManager}
 * {@link BearerSchemeAuthenticationManagerResolver} dispatched to.
 */
@Component
public class AuthContextFilter extends OncePerRequestFilter {

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		try {
			Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
			if (authentication != null && authentication.isAuthenticated()) {
				AuthContextHolder.set(resolve(authentication));
			}
			filterChain.doFilter(request, response);
		}
		finally {
			AuthContextHolder.clear();
		}
	}

	private AuthContext resolve(Authentication authentication) {
		if (authentication instanceof JwtAuthenticationToken jwtAuth) {
			Jwt jwt = jwtAuth.getToken();
			String sub = jwt.getSubject();
			String athleteId = jwt.getClaimAsString("athlete_id");
			if (athleteId == null || athleteId.isBlank()) {
				athleteId = sub;
			}
			return new AuthContext(sub, athleteId, parseScope(jwt.getClaimAsString("scope")), AuthContext.CredentialKind.JWT);
		}
		if (authentication instanceof BearerTokenAuthentication bearerAuth) {
			OAuth2AuthenticatedPrincipal principal = (OAuth2AuthenticatedPrincipal) bearerAuth.getPrincipal();
			String sub = principal.getName();
			List<String> scopeAttribute = principal.getAttribute("scope");
			Set<String> scopes = scopeAttribute == null ? Set.of() : Set.copyOf(scopeAttribute);
			return AuthContext.self(sub, scopes, AuthContext.CredentialKind.OAUTH2);
		}
		if (authentication instanceof PersonalAccessTokenAuthentication patAuth) {
			Set<String> scopes = Set.copyOf(patAuth.token().getScopes());
			return AuthContext.self((String) patAuth.getPrincipal(), scopes, AuthContext.CredentialKind.PERSONAL_ACCESS_TOKEN);
		}
		throw new IllegalStateException("Unrecognized authentication type: " + authentication.getClass());
	}

	private Set<String> parseScope(String scope) {
		if (scope == null || scope.isBlank()) {
			return Set.of();
		}
		return Set.of(scope.split(" "));
	}
}
