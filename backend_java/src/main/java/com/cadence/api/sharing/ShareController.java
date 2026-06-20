package com.cadence.api.sharing;

import com.cadence.api.common.error.ForbiddenException;
import com.cadence.api.common.paging.DataListResponse;
import com.cadence.api.security.AuthContextHolder;
import com.cadence.api.sharing.dto.CreateShareRequest;
import com.cadence.api.sharing.dto.ShareResponse;
import com.cadence.api.sharing.dto.UpdateShareRequest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Shares are always managed as the owner ({@code sub}) - there's no delegation here, unlike
 * gear/workouts which can act on an athlete a coach has been granted access to.
 */
@RestController
public class ShareController {

	private final SharingService sharingService;
	private final UserService userService;

	public ShareController(SharingService sharingService, UserService userService) {
		this.sharingService = sharingService;
		this.userService = userService;
	}

	@GetMapping("/v1/shares")
	public DataListResponse<ShareResponse> listShares() {
		String ownerId = AuthContextHolder.get().sub();
		return new DataListResponse<>(sharingService.listSharesGrantedBy(ownerId).stream()
				.map(sharingService::toResponse).toList());
	}

	@PostMapping("/v1/shares")
	@ResponseStatus(HttpStatus.CREATED)
	public ShareResponse createShare(@Valid @RequestBody CreateShareRequest request) {
		User owner = userService.getById(AuthContextHolder.get().sub());
		UserRelationship relationship = sharingService.createShare(owner, request);
		return sharingService.toResponse(relationship);
	}

	@PatchMapping("/v1/shares/{id}")
	public ShareResponse updateShare(@PathVariable String id, @Valid @RequestBody UpdateShareRequest request) {
		requireOwned(id);
		return sharingService.updateRoleAndRespond(id, request.role());
	}

	@DeleteMapping("/v1/shares/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteShare(@PathVariable String id) {
		requireOwned(id);
		sharingService.deleteShare(id);
	}

	private void requireOwned(String id) {
		UserRelationship relationship = sharingService.getShare(id);
		String sub = AuthContextHolder.get().sub();
		if (!relationship.getOwner().getId().equals(sub)) {
			throw new ForbiddenException("This share does not belong to you.");
		}
	}
}
