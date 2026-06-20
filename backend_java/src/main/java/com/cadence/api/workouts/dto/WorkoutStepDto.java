package com.cadence.api.workouts.dto;

import com.cadence.api.workouts.StepEndType;
import com.cadence.api.workouts.StepKind;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

public record WorkoutStepDto(
		@NotNull StepKind kind,
		@NotNull StepEndType endType,
		Integer duration,
		Integer distance,
		Double targetPct,
		Integer repeat) {

	@JsonIgnore
	@AssertTrue(message = "duration is required when end_type is time; distance is required when end_type is distance.")
	public boolean isEndTypeConsistent() {
		if (endType == null) {
			return true;
		}
		if (endType == StepEndType.TIME) {
			return duration != null;
		}
		if (endType == StepEndType.DISTANCE) {
			return distance != null;
		}
		return true;
	}
}
