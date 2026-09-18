package com.cadence.api.mcp.dto;

import com.cadence.api.activities.dto.ActivityResponse;
import com.cadence.api.common.domain.Sport;
import java.time.Instant;
import java.util.List;

/**
 * Trimmed for {@code get_activity} - summary fields plus a handful more that only matter once
 * you're looking at a single activity. Still drops threshold-history and multisport/duplicate
 * linkage arrays (see {@link ActivityResponse}'s field comments) - re-derivable via other tools
 * if ever needed, and rarely relevant to a conversational assistant.
 */
public record McpActivityDetail(
		String id, String name, Sport sport, Instant startDate, int movingTime, double distanceKm,
		Integer avgPower, Integer avgHr, int tss, Double intensity,
		Integer ascent, Integer calories, String trainingEffectLabel,
		List<String> tags, String workoutId, String bikeId, String shoeId,
		Double avgAirTemp, Integer avgHumidity,
		Double avgHeatStrain, Double maxHeatStrain, Double avgCoreTemp, Double maxCoreTemp,
		Double avgSkinTemp, Double maxSkinTemp,
		Double startWeightKg, Double endWeightKg, Integer fluidsMl) {

	public static McpActivityDetail from(ActivityResponse activity) {
		return new McpActivityDetail(
				activity.id(), activity.name(), activity.sport(), activity.startDate(), activity.movingTime(),
				activity.distanceKm(), activity.avgPower(), activity.avgHr(), activity.tss(), activity.intensity(),
				activity.ascent(), activity.calories(), activity.trainingEffectLabel(),
				activity.tags(), activity.workoutId(), activity.bikeId(), activity.shoeId(),
				activity.avgAirTemp(), activity.avgHumidity(),
				activity.avgHeatStrain(), activity.maxHeatStrain(), activity.avgCoreTemp(), activity.maxCoreTemp(),
				activity.avgSkinTemp(), activity.maxSkinTemp(),
				activity.startWeightKg(), activity.endWeightKg(), activity.fluidsMl());
	}
}
