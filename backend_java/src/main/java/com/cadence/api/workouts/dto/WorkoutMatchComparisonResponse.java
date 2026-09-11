package com.cadence.api.workouts.dto;

import java.time.LocalDate;

public record WorkoutMatchComparisonResponse(String activityId, String name, LocalDate date, int movingTime,
		Integer avgPower, Integer avgHr, Double ef, Integer workBlockAvgPower, Double avgCoreTemp, Double avgAirTemp,
		Integer avgHumidity, int tss) {
}
