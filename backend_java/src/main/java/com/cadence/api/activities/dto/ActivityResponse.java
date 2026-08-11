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
		// The athlete's threshold(s) as of when this activity was created - see Activity's
		// Javadoc. suggestedFtp/etc are transient in-app state (a pending detected increase -
		// see ThresholdDetectionService), deliberately stripped before export (see
		// ExportWriter.writeActivities/ActivityResponse.withoutSuggestedThresholds).
		Integer ftpSnapshot, Integer criticalRunPowerSnapshot, String thresholdPaceSnapshot,
		Integer suggestedFtp, Integer suggestedCriticalRunPower, String suggestedThresholdPace,
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
	public ActivityResponse withoutSuggestedThresholds() {
		return new ActivityResponse(
				id, athleteId, sport, environment, hasGps, name, startDate, source, device, movingTime, distanceKm,
				distanceSource, avgPower, normPower, intensity, tss, avgHr, maxHr, ascent, maxPower, avgCadence,
				maxCadence, maxSpeed, totalDescent, elevationMin, elevationMax, calories, trimp, avgLeftBalancePct,
				ftpSnapshot, criticalRunPowerSnapshot, thresholdPaceSnapshot, null, null, "", startWeightKg, endWeightKg,
				fluidsMl, avgAirTemp, avgHumidity, aerobicTrainingEffect, anaerobicTrainingEffect, trainingEffectLabel,
				tags, workoutId, bikeId, shoeId, parentActivityId, childActivityIds, primaryActivityId,
				duplicateActivityIds);
	}
}
