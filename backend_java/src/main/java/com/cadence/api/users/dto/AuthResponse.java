package com.cadence.api.users.dto;

/** The shared {@code {athlete, tokens}} response shape for both registration and login. */
public record AuthResponse(UserResponse athlete, TokenResponse tokens) {
}
