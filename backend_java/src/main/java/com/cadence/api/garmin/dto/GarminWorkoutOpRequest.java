package com.cadence.api.garmin.dto;

import com.cadence.api.garmin.GarminWorkoutOpKind;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * What the browser asks the bridge to do on Garmin Connect. {@code workoutId} is always the
 * Cadence workout. UPSERT_WORKOUT carries the {@code tcx}; SCHEDULE and RESCHEDULE carry
 * {@code scheduledWorkoutId} and {@code date}; UNSCHEDULE carries {@code scheduledWorkoutId}.
 */
public record GarminWorkoutOpRequest(
		@NotNull GarminWorkoutOpKind kind,
		@NotBlank @Size(max = 40) String workoutId,
		@Size(max = 40) String scheduledWorkoutId,
		LocalDate date,
		@Size(max = 200) String name,
		@Size(max = 500_000) String tcx) {
}
