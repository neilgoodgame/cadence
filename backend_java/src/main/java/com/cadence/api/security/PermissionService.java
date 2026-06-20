package com.cadence.api.security;

import com.cadence.api.sharing.ShareRole;
import com.cadence.api.sharing.ShareStatus;
import com.cadence.api.sharing.UserRelationshipRepository;
import org.springframework.stereotype.Service;

/**
 * Whether the authenticated principal ({@code sub}) may read or write a given athlete's
 * data. Always either the athlete themself, or someone the athlete has granted an active
 * share to (any role for read; {@code coach} for write). Queried fresh per call - this
 * relationship changes rarely enough that caching isn't worth the invalidation complexity.
 */
@Service
public class PermissionService {

	private final UserRelationshipRepository userRelationshipRepository;

	public PermissionService(UserRelationshipRepository userRelationshipRepository) {
		this.userRelationshipRepository = userRelationshipRepository;
	}

	public boolean mayRead(String sub, String athleteId) {
		if (sub.equals(athleteId)) {
			return true;
		}
		return userRelationshipRepository.findByOwnerIdAndGranteeIdAndStatus(athleteId, sub, ShareStatus.ACTIVE)
				.isPresent();
	}

	public boolean mayWrite(String sub, String athleteId) {
		if (sub.equals(athleteId)) {
			return true;
		}
		return userRelationshipRepository.findByOwnerIdAndGranteeIdAndStatus(athleteId, sub, ShareStatus.ACTIVE)
				.filter(relationship -> relationship.getRole() == ShareRole.COACH)
				.isPresent();
	}
}
