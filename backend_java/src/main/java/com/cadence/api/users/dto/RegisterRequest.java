package com.cadence.api.users.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;

/**
 * Either {@code email}+{@code password}, or {@code provider}+{@code id_token} for a
 * social signup. There is no real Strava/Google/Apple integration behind {@code provider} -
 * the contract documents it for account creation only, and this implementation trusts the
 * supplied {@code id_token} at face value rather than verifying it against a third party.
 */
public record RegisterRequest(
		@NotBlank String name,
		String email,
		String password,
		String provider,
		String idToken) {

	@JsonIgnore
	@AssertTrue(message = "Provide either email+password (password at least 10 characters) or provider+id_token.")
	public boolean isCredentialShapeValid() {
		if (provider == null || provider.isBlank()) {
			return email != null && !email.isBlank() && password != null && password.length() >= 10;
		}
		return idToken != null && !idToken.isBlank();
	}
}
