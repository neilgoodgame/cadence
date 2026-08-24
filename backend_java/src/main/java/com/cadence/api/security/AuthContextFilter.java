package com.cadence.api.security;

import com.cadence.api.security.pat.PersonalAccessTokenAuthentication;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
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
 *
 * <p>Runs on every request through the default security filter chain, which - since the MCP
 * OAuth work added a real browser-rendered {@code /login} form in front of {@code /oauth/authorize}
 * (needed for a genuine third-party OAuth client like Claude's MCP connector, unlike the
 * first-party client which bypasses it entirely) - now also sees session-based
 * {@code UsernamePasswordAuthenticationToken}s, not just the three bearer-credential types. Those
 * never reach a REST resource endpoint that reads {@link AuthContextHolder} (the login/consent
 * pages don't use it), so {@link #resolve} skips anything it doesn't recognize instead of
 * crashing the request - found live via a real Claude Desktop connector test, where this threw a
 * 500 mid-flow and surfaced to Claude as a generic "Authorization ... failed" error.
 */
@Component
public class AuthContextFilter extends OncePerRequestFilter {

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		try {
			Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
			if (authentication != null && authentication.isAuthenticated()) {
				resolve(authentication).ifPresent(AuthContextHolder::set);
			}
			filterChain.doFilter(request, response);
		}
		finally {
			AuthContextHolder.clear();
		}
	}

	private Optional<AuthContext> resolve(Authentication authentication) {
		if (authentication instanceof JwtAuthenticationToken jwtAuth) {
			Jwt jwt = jwtAuth.getToken();
			String sub = jwt.getSubject();
			String athleteId = jwt.getClaimAsString("athlete_id");
			if (athleteId == null || athleteId.isBlank()) {
				athleteId = sub;
			}
			return Optional.of(
					new AuthContext(sub, athleteId, parseScope(jwt.getClaimAsString("scope")), AuthContext.CredentialKind.JWT));
		}
		if (authentication instanceof BearerTokenAuthentication bearerAuth) {
			OAuth2AuthenticatedPrincipal principal = (OAuth2AuthenticatedPrincipal) bearerAuth.getPrincipal();
			String sub = principal.getName();
			List<String> scopeAttribute = principal.getAttribute("scope");
			Set<String> scopes = scopeAttribute == null ? Set.of() : Set.copyOf(scopeAttribute);
			return Optional.of(AuthContext.self(sub, scopes, AuthContext.CredentialKind.OAUTH2));
		}
		if (authentication instanceof PersonalAccessTokenAuthentication patAuth) {
			Set<String> scopes = Set.copyOf(patAuth.token().getScopes());
			return Optional.of(
					AuthContext.self((String) patAuth.getPrincipal(), scopes, AuthContext.CredentialKind.PERSONAL_ACCESS_TOKEN));
		}
		return Optional.empty();
	}

	private Set<String> parseScope(String scope) {
		if (scope == null || scope.isBlank()) {
			return Set.of();
		}
		return Set.of(scope.split(" "));
	}
}
