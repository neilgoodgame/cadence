package com.cadence.api.workouts.dto;

import java.time.LocalDate;

public record WorkoutMatchResponse(String activityId, String name, LocalDate date, String method, Double confidence,
		Double compliance, int tss, int movingTime, double distanceKm, Integer avgPower) {
}
