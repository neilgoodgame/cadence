package com.cadence.api.security;

import com.cadence.api.security.pat.PersonalAccessTokenAuthentication;
import com.cadence.api.sharing.ShareStatus;
import com.cadence.api.sharing.UserRelationshipRepository;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
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

	private final UserRepository userRepository;
	private final UserRelationshipRepository userRelationshipRepository;

	public AuthContextFilter(UserRepository userRepository, UserRelationshipRepository userRelationshipRepository) {
		this.userRepository = userRepository;
		this.userRelationshipRepository = userRelationshipRepository;
	}

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
			String athleteId = virtualCoachDelegatedAthleteId(sub).orElse(sub);
			return Optional.of(new AuthContext(sub, athleteId, scopes, AuthContext.CredentialKind.OAUTH2));
		}
		if (authentication instanceof PersonalAccessTokenAuthentication patAuth) {
			Set<String> scopes = Set.copyOf(patAuth.token().getScopes());
			String sub = (String) patAuth.getPrincipal();
			String delegatedAthleteId = patAuth.token().getDelegatedAthleteId();
			String athleteId = (delegatedAthleteId == null || delegatedAthleteId.isBlank()) ? sub : delegatedAthleteId;
			return Optional.of(new AuthContext(sub, athleteId, scopes, AuthContext.CredentialKind.PERSONAL_ACCESS_TOKEN));
		}
		return Optional.empty();
	}

	/**
	 * Deliberately scoped to {@code isVirtual} accounts only, not "any coach with exactly one
	 * active relationship" - a real user's own OAuth2 session (the web app's normal login) must
	 * never silently start showing someone else's data just because they happen to coach one
	 * athlete. A virtual account has no legitimate "self" view at all (see User.isVirtual's
	 * Javadoc), and is only ever linked to exactly one athlete by construction, so this is
	 * unambiguous.
	 */
	Optional<String> virtualCoachDelegatedAthleteId(String userId) {
		User user = userRepository.findById(userId).orElse(null);
		if (user == null || !user.isVirtual()) {
			return Optional.empty();
		}
		return userRelationshipRepository.findByGranteeIdAndStatus(userId, ShareStatus.ACTIVE)
				.stream()
				.findFirst()
				.map(relationship -> relationship.getOwner().getId());
	}

	private Set<String> parseScope(String scope) {
		if (scope == null || scope.isBlank()) {
			return Set.of();
		}
		return Set.of(scope.split(" "));
	}
}
