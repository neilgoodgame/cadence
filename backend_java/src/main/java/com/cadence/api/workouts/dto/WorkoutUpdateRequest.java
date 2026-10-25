package com.cadence.api.workouts.dto;

import jakarta.validation.Valid;
import java.util.List;

/**
 * {@code folderId}: omit to leave the workout's folder untouched; {@code null} or {@code ""}
 * clears it; any other value re-assigns it (validated to belong to the caller in the service).
 * No {@code tags} field - same as ActivityUpdateRequest's own gap, tags are never settable via
 * the main update, only via the dedicated attach/detach endpoints (WorkoutController's
 * tagWorkout/untagWorkout).
 */
public record WorkoutUpdateRequest(String name, List<@Valid WorkoutStepDto> steps, String folderId) {
}
