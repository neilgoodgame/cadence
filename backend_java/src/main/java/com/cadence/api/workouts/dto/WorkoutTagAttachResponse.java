package com.cadence.api.workouts.dto;

import com.cadence.api.activities.dto.TagResponse;

public record WorkoutTagAttachResponse(String workoutId, TagResponse tag) {
}
