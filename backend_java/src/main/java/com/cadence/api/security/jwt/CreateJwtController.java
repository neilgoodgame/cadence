package com.cadence.api.security.jwt;

import com.cadence.api.common.error.ForbiddenException;
import com.cadence.api.common.error.ValidationException;
import com.cadence.api.security.AuthContext;
import com.cadence.api.security.AuthContextHolder;
import com.cadence.api.sharing.ShareRole;
import com.cadence.api.sharing.ShareStatus;
import com.cadence.api.sharing.UserRelationshipRepository;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Mints a delegated JWT for the caller (or, with an active coach/viewer share, for an
 * athlete the caller has been granted access to). {@code sub} is always the caller;
 * {@code athlete_id} is whose data the resulting token authorizes.
 */
@RestController
public class CreateJwtController {

	private static final Set<String> WRITE_SCOPES = Set.of(
			"activities:write", "workouts:write", "calendar:write", "gear:write");
	private static final List<String> DEFAULT_SCOPES = List.of("activities:read");
	private static final int DEFAULT_EXPIRES_IN = 3600;
	private static final int MAX_EXPIRES_IN = 86400;

	private final JwtIssuer jwtIssuer;
	private final UserRelationshipRepository userRelationshipRepository;

	public CreateJwtController(JwtIssuer jwtIssuer, UserRelationshipRepository userRelationshipRepository) {
		this.jwtIssuer = jwtIssuer;
		this.userRelationshipRepository = userRelationshipRepository;
	}

	@PostMapping("/v1/auth/jwt")
	@ResponseStatus(HttpStatus.CREATED)
	public JwtTokenResponse createJwt(@RequestBody(required = false) CreateJwtRequest body) {
		CreateJwtRequest request = body != null ? body : new CreateJwtRequest(null, null, null);
		AuthContext context = AuthContextHolder.get();
		String sub = context.sub();
		String athleteId = request.athleteId() != null ? request.athleteId() : sub;
		List<String> scopes = request.scopes() != null ? request.scopes() : DEFAULT_SCOPES;
		int expiresIn = request.expiresIn() != null ? request.expiresIn() : DEFAULT_EXPIRES_IN;

		if (scopes.isEmpty()) {
			throw new ValidationException("scopes must be a non-empty array of strings.", "scopes");
		}
		if (expiresIn <= 0 || expiresIn > MAX_EXPIRES_IN) {
			throw new ValidationException("expires_in must be a positive integer up to " + MAX_EXPIRES_IN + ".", "expires_in");
		}

		Set<String> callerScopes = context.scopes();
		if (!callerScopes.isEmpty() && !callerScopes.containsAll(scopes)) {
			throw new ValidationException("scopes must be a subset of the caller's own scopes.", "scopes");
		}

		if (!athleteId.equals(sub)) {
			var relationship = userRelationshipRepository
					.findByOwnerIdAndGranteeIdAndStatus(athleteId, sub, ShareStatus.ACTIVE)
					.orElseThrow(() -> new ForbiddenException("You do not have access to that athlete's data."));
			if (relationship.getRole() == ShareRole.VIEWER && scopes.stream().anyMatch(WRITE_SCOPES::contains)) {
				throw new ForbiddenException("Viewer access is read-only.");
			}
		}

		JwtIssuer.Minted minted = jwtIssuer.mint(sub, athleteId, scopes, expiresIn);
		return new JwtTokenResponse(minted.token(), "Bearer", expiresIn, minted.claims());
	}
}
