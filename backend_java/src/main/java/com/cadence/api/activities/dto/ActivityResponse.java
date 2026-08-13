package com.cadence.api.activities.dto;

import com.cadence.api.activities.DistanceSource;
import com.cadence.api.activities.Environment;
import com.cadence.api.common.domain.Sport;
import java.time.Instant;
import java.util.List;

public record ActivityResponse(
		String id, String athleteId, Sport sport, Environment environment, boolean hasGps, String name,
		Instant startDate, String source, String device, int movingTime, double distanceKm, DistanceSource distanceSource,
		Integer avgPower, Integer normPower, Double intensity, int tss, Integer avgHr, Integer maxHr, Integer ascent,
		Integer maxPower, Integer avgCadence, Integer maxCadence, Double maxSpeed, Integer totalDescent,
		Integer elevationMin, Integer elevationMax, Integer calories, Double trimp, Double avgLeftBalancePct,
		// Every ThresholdHistory row this activity is (or was) the source of - see
		// ActivityThresholdHistoryEntry. Empty for the vast majority of activities. Deliberately
		// stripped before export (see ExportWriter.writeActivities/withoutTransientThresholdState):
		// re-derivable from the top-level "threshold_history" section's own sourceActivityId links,
		// so embedding it here too would just be duplicated data.
		List<ActivityThresholdHistoryEntry> thresholdHistory,
		Double startWeightKg, Double endWeightKg, Integer fluidsMl, Double avgAirTemp, Integer avgHumidity,
		Double aerobicTrainingEffect, Double anaerobicTrainingEffect, String trainingEffectLabel,
		List<String> tags, String workoutId, String bikeId, String shoeId,
		// Multisport linkage: children carry parentActivityId, the parent lists childActivityIds
		// in start-date order (empty for every non-multisport activity).
		String parentActivityId, List<String> childActivityIds,
		// Duplicate-recording linkage: a duplicate carries primaryActivityId, the primary lists
		// duplicateActivityIds in start-date order (empty everywhere else).
		String primaryActivityId, List<String> duplicateActivityIds) {

	/** Export payload only - see the field comment above. */
	public ActivityResponse withoutTransientThresholdState() {
		return new ActivityResponse(
				id, athleteId, sport, environment, hasGps, name, startDate, source, device, movingTime, distanceKm,
				distanceSource, avgPower, normPower, intensity, tss, avgHr, maxHr, ascent, maxPower, avgCadence,
				maxCadence, maxSpeed, totalDescent, elevationMin, elevationMax, calories, trimp, avgLeftBalancePct,
				List.of(), startWeightKg, endWeightKg, fluidsMl, avgAirTemp, avgHumidity, aerobicTrainingEffect,
				anaerobicTrainingEffect, trainingEffectLabel, tags, workoutId, bikeId, shoeId, parentActivityId,
				childActivityIds, primaryActivityId, duplicateActivityIds);
	}
}
