package com.cadence.api.activities.dto;

public record ActivityWorkoutMatchCandidateResponse(String workoutId, String workoutName, double correlation,
		double coverage, Integer impliedFtp, int durationDiffSeconds) {
}
