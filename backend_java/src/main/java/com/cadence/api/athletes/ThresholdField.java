package com.cadence.api.athletes;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum ThresholdField {
	FTP, CRITICAL_RUN_POWER, THRESHOLD_PACE;

	/** The fields an activity of {@code sport} can define a threshold for (bike: FTP; run:
	 * critical run power and threshold pace; every other sport: none). Mirrors {@code
	 * ThresholdHistoryService#fieldAppliesTo}. */
	public static java.util.Set<ThresholdField> forSport(com.cadence.api.common.domain.Sport sport) {
		return switch (sport) {
			case BIKE -> java.util.EnumSet.of(FTP);
			case RUN -> java.util.EnumSet.of(CRITICAL_RUN_POWER, THRESHOLD_PACE);
			default -> java.util.EnumSet.noneOf(ThresholdField.class);
		};
	}

	@JsonValue
	public String wireValue() {
		return name().toLowerCase();
	}

	@JsonCreator
	public static ThresholdField fromWireValue(String value) {
		return ThresholdField.valueOf(value.toUpperCase());
	}
}
