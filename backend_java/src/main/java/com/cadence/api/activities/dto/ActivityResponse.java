package com.cadence.api.activities.dto;

import com.cadence.api.activities.DistanceSource;
import com.cadence.api.activities.Environment;
import com.cadence.api.common.domain.Sport;
import java.time.Instant;
import java.util.List;

public record ActivityResponse(
		String id, String athleteId, Sport sport, Environment environment, boolean hasGps, String name,
		Instant startDate, String source, int movingTime, double distanceKm, DistanceSource distanceSource,
		Integer avgPower, Integer normPower, Double intensity, int tss, Integer avgHr, Integer maxHr, Integer ascent,
		Double startWeightKg, Double endWeightKg, Integer fluidsMl, Double avgAirTemp, Integer avgHumidity,
		List<String> tags, String workoutId, String bikeId, String shoeId) {
}
