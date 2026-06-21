package com.cadence.api.workouts.dto;

import jakarta.validation.Valid;
import java.util.List;

public record WorkoutUpdateRequest(String name, List<@Valid WorkoutStepDto> steps) {
}
