package com.cadence.api.tokens.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.time.LocalDate;
import java.util.List;

/** {@code athleteId} is optional and only meaningful when it differs from the caller's own id -
 * mints a token delegated to an athlete the caller actively coaches (or views), checked by
 * {@link com.cadence.api.sharing.DelegationPolicy}. Omitted (or equal to the caller), the token
 * is an ordinary self-scoped one, unchanged from before delegation existed. */
public record CreateAccessTokenRequest(
		@NotBlank String name, @NotEmpty List<String> scopes, LocalDate expiresAt, String athleteId) {
}
