package com.cadence.api.workouts.dto;

import com.cadence.api.common.domain.Sport;
import java.util.List;

/** A step tree inferred from an activity's laps - not persisted, ready to open as an unsaved draft in the builder. */
public record InferredWorkoutResponse(String name, Sport sport, List<WorkoutStepDto> steps) {
}
