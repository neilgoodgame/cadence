package com.cadence.api.activities.dto;

import jakarta.validation.constraints.NotBlank;

/** A bare tag name - used for both creating a standalone tag and renaming one. */
public record TagNameRequest(@NotBlank(message = "name cannot be empty.") String name) {
}
