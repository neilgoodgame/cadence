package com.cadence.api.sharing;

import com.cadence.api.common.error.ForbiddenException;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Shared coach-delegation authorization check: does {@code coachId} have an active coach/viewer
 * relationship granting it access to {@code athleteId}, and (if viewer-only) do the requested
 * scopes stay read-only. Reused by delegated JWT minting ({@code CreateJwtController}) and
 * delegated personal access token creation ({@code AccessTokenService}).
 */
@Component
public class DelegationPolicy {

	private static final Set<String> WRITE_SCOPES = Set.of(
			"activities:write", "workouts:write", "calendar:write", "gear:write");

	private final UserRelationshipRepository userRelationshipRepository;

	public DelegationPolicy(UserRelationshipRepository userRelationshipRepository) {
		this.userRelationshipRepository = userRelationshipRepository;
	}

	public void requireActiveCoachAccess(String athleteId, String coachId, List<String> requestedScopes) {
		UserRelationship relationship = userRelationshipRepository
				.findByOwnerIdAndGranteeIdAndStatus(athleteId, coachId, ShareStatus.ACTIVE)
				.orElseThrow(() -> new ForbiddenException("You do not have access to that athlete's data."));
		if (relationship.getRole() == ShareRole.VIEWER && requestedScopes.stream().anyMatch(WRITE_SCOPES::contains)) {
			throw new ForbiddenException("Viewer access is read-only.");
		}
	}
}
