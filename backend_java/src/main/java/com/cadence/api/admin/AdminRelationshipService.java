package com.cadence.api.admin;

import com.cadence.api.admin.dto.AdminRelationshipResponse;
import com.cadence.api.sharing.SharingService;
import com.cadence.api.sharing.UserRelationship;
import com.cadence.api.sharing.UserRelationshipRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminRelationshipService {

	private final UserRelationshipRepository userRelationshipRepository;
	private final SharingService sharingService;

	public AdminRelationshipService(UserRelationshipRepository userRelationshipRepository, SharingService sharingService) {
		this.userRelationshipRepository = userRelationshipRepository;
		this.sharingService = sharingService;
	}

	@Transactional(readOnly = true)
	public List<AdminRelationshipResponse> list() {
		return userRelationshipRepository.findAllWithUsersOrderByCreatedDesc().stream().map(this::toResponse).toList();
	}

	/** No ownership gate, unlike ShareController.deleteShare - an admin can revoke any
	 * relationship, not just ones they granted themselves. sharingService.getShare(id) still
	 * throws NotFoundException for a bad id, same as the self-service path. */
	@Transactional
	public void revoke(String id) {
		sharingService.getShare(id);
		sharingService.deleteShare(id);
	}

	private AdminRelationshipResponse toResponse(UserRelationship relationship) {
		return new AdminRelationshipResponse(
				relationship.getId(),
				relationship.getGrantee().getName(),
				relationship.getOwner().getName(),
				relationship.getRole(),
				relationship.getCreated());
	}
}
