package com.cadence.api.sharing;

import com.cadence.api.common.error.ConflictException;
import com.cadence.api.common.error.NotFoundException;
import com.cadence.api.common.error.ValidationException;
import com.cadence.api.sharing.dto.CreateShareRequest;
import com.cadence.api.sharing.dto.ShareResponse;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SharingService {

	private final UserRelationshipRepository userRelationshipRepository;
	private final UserRepository userRepository;

	public SharingService(UserRelationshipRepository userRelationshipRepository, UserRepository userRepository) {
		this.userRelationshipRepository = userRelationshipRepository;
		this.userRepository = userRepository;
	}

	public List<UserRelationship> listSharesGrantedBy(String ownerId) {
		return userRelationshipRepository.findByOwnerIdWithUsersOrderByCreatedDesc(ownerId);
	}

	/** Athletes this user coaches (or views) - every active relationship where they're the grantee. */
	public List<UserRelationship> listCoachingContexts(String granteeId) {
		return userRelationshipRepository.findByGranteeIdAndStatusActiveWithUsers(granteeId);
	}

	public ShareResponse toResponse(UserRelationship relationship) {
		User grantee = relationship.getGrantee();
		String handle = grantee.getHandle() != null ? "@" + grantee.getHandle() : null;
		LocalDate since = relationship.getCreated().atZone(ZoneOffset.UTC).toLocalDate();
		return new ShareResponse(relationship.getId(), grantee.getName(), handle, relationship.getRole(),
				relationship.getStatus(), since);
	}

	@Transactional
	public UserRelationship createShare(User owner, CreateShareRequest request) {
		User grantee = resolveInvitee(request.invitee());
		if (grantee.getId().equals(owner.getId())) {
			throw new ValidationException("You cannot share with yourself.", "invitee");
		}
		if (userRelationshipRepository.findByOwnerIdAndGranteeId(owner.getId(), grantee.getId()).isPresent()) {
			throw new ConflictException("You have already invited this person.");
		}
		UserRelationship relationship = new UserRelationship();
		relationship.setOwner(owner);
		relationship.setGrantee(grantee);
		relationship.setRole(request.role());
		relationship.setStatus(ShareStatus.PENDING);
		return userRelationshipRepository.save(relationship);
	}

	public UserRelationship getShare(String id) {
		return userRelationshipRepository.findByIdWithUsers(id).orElseThrow(() -> new NotFoundException("No such share."));
	}

	/**
	 * Reloads, mutates, saves, and maps to the response DTO within a single transaction.
	 * Merging a detached entity can reset an already-initialized association back to an
	 * uninitialized lazy proxy, so mapping the result of a save() outside its own transaction
	 * is not safe even for associations the update itself didn't touch.
	 */
	@Transactional
	public ShareResponse updateRoleAndRespond(String id, ShareRole role) {
		UserRelationship relationship = getShare(id);
		relationship.setRole(role);
		UserRelationship saved = userRelationshipRepository.save(relationship);
		return toResponse(saved);
	}

	@Transactional
	public void deleteShare(String id) {
		userRelationshipRepository.deleteById(id);
	}

	private User resolveInvitee(String invitee) {
		String trimmed = invitee.trim();
		if (trimmed.startsWith("@")) {
			String handle = trimmed.substring(1);
			return userRepository.findByHandleIgnoreCase(handle)
					.orElseThrow(() -> new ValidationException("No user with that handle.", "invitee"));
		}
		return userRepository.findByEmailIgnoreCase(trimmed)
				.orElseThrow(() -> new ValidationException("No user with that email.", "invitee"));
	}
}
