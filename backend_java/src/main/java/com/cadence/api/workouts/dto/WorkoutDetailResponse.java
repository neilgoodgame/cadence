package com.cadence.api.workouts.dto;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.util.List;

public record WorkoutDetailResponse(@JsonUnwrapped WorkoutResponse workout, List<WorkoutStepDto> steps) {
}
