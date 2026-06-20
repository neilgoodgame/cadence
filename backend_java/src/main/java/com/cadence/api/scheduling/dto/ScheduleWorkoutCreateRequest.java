package com.cadence.api.scheduling.dto;

import com.cadence.api.scheduling.TimeOfDay;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

public record ScheduleWorkoutCreateRequest(
		@NotBlank String workoutId, @NotBlank String athleteId, @NotNull LocalDate date, TimeOfDay timeOfDay) {
}
