package com.cadence.api.tokens.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.time.LocalDate;
import java.util.List;

public record CreateAccessTokenRequest(@NotBlank String name, @NotEmpty List<String> scopes, LocalDate expiresAt) {
}
