package com.cadence.api.garmin.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** The bridge's report for an operation: {@code status} is DONE or FAILED. */
public record GarminWorkoutOpResultRequest(
		@NotNull String status,
		Long garminWorkoutId,
		Long garminScheduleId,
		@Size(max = 2000) String error) {
}
