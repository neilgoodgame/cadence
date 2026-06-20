package com.cadence.api.workouts.dto;

import com.cadence.api.common.domain.Sport;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record WorkoutCreateRequest(
		@NotBlank String name,
		@NotNull Sport sport,
		@NotEmpty List<@Valid WorkoutStepDto> steps) {
}
