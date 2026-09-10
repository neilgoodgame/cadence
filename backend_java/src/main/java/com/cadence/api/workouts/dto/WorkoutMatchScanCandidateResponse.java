package com.cadence.api.workouts.dto;

import java.time.LocalDate;

public record WorkoutMatchScanCandidateResponse(String activityId, String name, LocalDate date, double correlation,
		int durationDiffSeconds, double coverage, Integer impliedFtp, int movingTime, Integer avgPower) {
}
