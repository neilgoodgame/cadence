package com.cadence.api.sharing;

import com.cadence.api.common.error.ConflictException;
import com.cadence.api.common.error.NotFoundException;
import com.cadence.api.common.error.ValidationException;
import com.cadence.api.security.pat.PersonalAccessTokenRepository;
import com.cadence.api.sharing.dto.CreateShareRequest;
import com.cadence.api.sharing.dto.CreateVirtualCoachRequest;
import com.cadence.api.sharing.dto.ShareResponse;
import com.cadence.api.sharing.dto.VirtualCoachCreatedResponse;
import com.cadence.api.tokens.AccessTokenService;
import com.cadence.api.tokens.dto.AccessTokenCreatedResponse;
import com.cadence.api.tokens.dto.CreateAccessTokenRequest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SharingService {

	private static final String PASSWORD_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
	private static final int PASSWORD_LENGTH = 24;
	private static final SecureRandom RANDOM = new SecureRandom();

	private final UserRelationshipRepository userRelationshipRepository;
	private final UserRepository userRepository;
	private final PersonalAccessTokenRepository personalAccessTokenRepository;
	private final AccessTokenService accessTokenService;
	private final PasswordEncoder passwordEncoder;

	public SharingService(
			UserRelationshipRepository userRelationshipRepository, UserRepository userRepository,
			PersonalAccessTokenRepository personalAccessTokenRepository, AccessTokenService accessTokenService,
			PasswordEncoder passwordEncoder) {
		this.userRelationshipRepository = userRelationshipRepository;
		this.userRepository = userRepository;
		this.personalAccessTokenRepository = personalAccessTokenRepository;
		this.accessTokenService = accessTokenService;
		this.passwordEncoder = passwordEncoder;
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
				relationship.getStatus(), since, grantee.isVirtual());
	}

	@Transactional
	public UserRelationship createShare(User owner, CreateShareRequest request) {
		User grantee = resolveInvitee(request.invitee());
		if (grantee.getId().equals(owner.getId())) {
			throw new ValidationException("You cannot share with yourself.", "invitee");
		}
		if (grantee.isVirtual()) {
			// Virtual coaches are only ever created (and linked) via createVirtualCoach below -
			// they have no discoverable email/handle to invite by, but guard the invariant
			// explicitly rather than relying on that alone.
			throw new ValidationException("No user found with that email or handle.", "invitee");
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

	/**
	 * Creates a synthetic "virtual coach" account (no real inbox - see User.isVirtual's Javadoc,
	 * but a real, usable password so it can complete an interactive OAuth login the same way any
	 * other account does) belonging to nobody but this one relationship, an already-{@code
	 * ACTIVE} coach relationship granting it access to {@code athlete} (no invite/accept needed -
	 * the athlete created it themselves), and a personal access token delegated to {@code
	 * athlete} for MCP clients that accept a bearer token directly. Password and token secret are
	 * both revealed once in the response and never retrievable again.
	 */
	@Transactional
	public VirtualCoachCreatedResponse createVirtualCoach(User athlete, CreateVirtualCoachRequest request) {
		String password = generatePassword();
		User coach = new User();
		coach.setName(request.name());
		coach.setEmail("virtual+" + UUID.randomUUID() + "@social.cadence.invalid");
		coach.setEmailVerified(true);
		coach.setCoach(true);
		coach.setVirtual(true);
		coach.setPassword(passwordEncoder.encode(password));
		userRepository.save(coach);

		UserRelationship relationship = new UserRelationship();
		relationship.setOwner(athlete);
		relationship.setGrantee(coach);
		relationship.setRole(ShareRole.COACH);
		relationship.setStatus(ShareStatus.ACTIVE);
		userRelationshipRepository.save(relationship);

		CreateAccessTokenRequest tokenRequest =
				new CreateAccessTokenRequest("MCP access", request.scopes(), null, athlete.getId());
		AccessTokenService.CreatedToken created = accessTokenService.create(coach, tokenRequest, Set.of());
		AccessTokenCreatedResponse tokenResponse =
				new AccessTokenCreatedResponse(accessTokenService.toResponse(created.token()), created.secret());

		return new VirtualCoachCreatedResponse(toResponse(relationship), coach.getEmail(), password, tokenResponse);
	}

	private String generatePassword() {
		StringBuilder sb = new StringBuilder(PASSWORD_LENGTH);
		for (int i = 0; i < PASSWORD_LENGTH; i++) {
			sb.append(PASSWORD_ALPHABET.charAt(RANDOM.nextInt(PASSWORD_ALPHABET.length())));
		}
		return sb.toString();
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
		UserRelationship relationship = getShare(id);
		User grantee = relationship.getGrantee();
		if (grantee.isVirtual()) {
			// A virtual coach exists only for this one relationship (see createVirtualCoach) - it
			// has a real usable password and a delegated token, either of which would otherwise
			// keep working indefinitely after revoke, so deleting the whole account is the actual
			// invalidation, not just deleting the relationship row. The relationship itself is
			// still deleted explicitly (not left to the ON DELETE CASCADE FK alone) - Hibernate's
			// persistence context still holds it as a managed, unmodified entity referencing
			// `grantee`, and deleting the row it points to out from under it that way raises
			// TransientPropertyValueException at flush time even though the raw SQL cascade would
			// have been fine.
			userRelationshipRepository.deleteById(id);
			userRepository.delete(grantee);
			return;
		}
		// A delegated token's authorization is resolved from the athleteId stored on the token
		// itself (AuthContextFilter), not re-checked against the relationship on every request -
		// so revoking here must actively invalidate any token this grantee minted against this
		// owner, or it would keep working indefinitely.
		personalAccessTokenRepository.deleteByUserIdAndDelegatedAthleteId(grantee.getId(), relationship.getOwner().getId());
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
