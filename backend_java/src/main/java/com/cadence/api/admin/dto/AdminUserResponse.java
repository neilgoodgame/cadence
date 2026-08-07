package com.cadence.api.admin.dto;

import java.time.Instant;

public record AdminUserResponse(
		String id, String name, String email, Instant dateJoined, boolean isCoach, boolean isAdmin) {
}
