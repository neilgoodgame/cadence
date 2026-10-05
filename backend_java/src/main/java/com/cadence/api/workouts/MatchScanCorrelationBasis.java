package com.cadence.api.workouts;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Which scoring method a {@link WorkoutMatchScan}'s correlation pass uses - see
 * {@link WorkoutMatchScan#getCorrelationBasis()}'s own Javadoc for what "LAPS" does and
 * requires. */
public enum MatchScanCorrelationBasis {
	POWER, LAPS;

	@JsonValue
	public String wireValue() {
		return name().toLowerCase();
	}

	@JsonCreator
	public static MatchScanCorrelationBasis fromWireValue(String value) {
		return MatchScanCorrelationBasis.valueOf(value.toUpperCase());
	}
}
