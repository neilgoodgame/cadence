package com.cadence.api.security;

import com.cadence.api.common.error.ForbiddenException;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import org.springframework.stereotype.Component;

/** Thin wrapper around {@link AuthContextHolder} + {@link PermissionService} for the common "may this caller act on this athlete's data" checks. */
@Component
public class AccessGuard {

	private final PermissionService permissionService;
	private final UserRepository userRepository;

	public AccessGuard(PermissionService permissionService, UserRepository userRepository) {
		this.permissionService = permissionService;
		this.userRepository = userRepository;
	}

	/** Returns the caller's id if they may read {@code athleteId}'s data, else throws. */
	public String requireRead(String athleteId) {
		String sub = AuthContextHolder.get().sub();
		if (!permissionService.mayRead(sub, athleteId)) {
			throw new ForbiddenException("You do not have access to this athlete's data.");
		}
		return sub;
	}

	/** Returns the caller's id if they may write {@code athleteId}'s data, else throws. */
	public String requireWrite(String athleteId) {
		String sub = AuthContextHolder.get().sub();
		if (!permissionService.mayWrite(sub, athleteId)) {
			throw new ForbiddenException("You do not have write access to this athlete's data.");
		}
		return sub;
	}

	/** The athlete whose data the current request is authorized against (defaults to the caller). */
	public String effectiveAthleteId() {
		return AuthContextHolder.get().athleteId();
	}

	/** Returns the real signed-in principal's id (not any delegated athleteId) if they're a
	 * platform admin, else throws. Admin-ness is never delegated. */
	public String requireAdmin() {
		String sub = AuthContextHolder.get().sub();
		User user = userRepository.findById(sub).orElseThrow(() -> new ForbiddenException("This action requires admin access."));
		if (!user.isAdmin()) {
			throw new ForbiddenException("This action requires admin access.");
		}
		return sub;
	}
}
