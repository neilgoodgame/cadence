package com.cadence.api.workouts;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Which of a workout's planned totals {@link WorkoutMatchScanService}'s candidate pre-filter
 * compares each activity against - see {@link WorkoutMatchScan#getDurationBasis()}'s own
 * Javadoc for why {@code DISTANCE} exists at all. */
public enum MatchScanDurationBasis {
	TIME, DISTANCE;

	@JsonValue
	public String wireValue() {
		return name().toLowerCase();
	}

	@JsonCreator
	public static MatchScanDurationBasis fromWireValue(String value) {
		return MatchScanDurationBasis.valueOf(value.toUpperCase());
	}
}
