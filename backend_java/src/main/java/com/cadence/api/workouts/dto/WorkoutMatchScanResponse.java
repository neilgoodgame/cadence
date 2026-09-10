package com.cadence.api.workouts.dto;

import com.cadence.api.workouts.WorkoutMatchScanStatus;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;

public record WorkoutMatchScanResponse(String id, String workoutId, WorkoutMatchScanStatus status,
		Integer totalCandidates, int processedCandidates, String errorMessage, Instant createdAt, Instant completedAt,
		List<WorkoutMatchScanCandidateResponse> candidates) {

	@JsonProperty("object")
	public String object() {
		return "workout_match_scan";
	}
}
