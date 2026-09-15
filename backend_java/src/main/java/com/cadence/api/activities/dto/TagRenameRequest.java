package com.cadence.api.activities.dto;

import jakarta.validation.constraints.NotBlank;

public record TagRenameRequest(@NotBlank(message = "name cannot be empty.") String name) {
}
