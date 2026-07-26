package com.cadence.api.workouts.dto;

import jakarta.validation.Valid;
import java.util.List;

/**
 * {@code folderId}: omit to leave the workout's folder untouched; {@code null} or {@code ""}
 * clears it; any other value re-assigns it (validated to belong to the caller in the service).
 */
public record WorkoutUpdateRequest(String name, List<@Valid WorkoutStepDto> steps, String folderId, List<String> tags) {
}
