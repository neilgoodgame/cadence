package com.cadence.api.tokens.dto;

import java.time.LocalDate;
import java.util.List;

public record AccessTokenResponse(
		String id, String name, String prefix, List<String> scopes, LocalDate created, LocalDate expiresAt, LocalDate lastUsed) {
}
