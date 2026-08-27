package com.cadence.api.workouts;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Only meaningful when {@link WorkoutStep#getTargetType()} is {@link TargetType#POWER} - which
 * unit targetLow/targetHigh are in (percent of FTP/criticalRunPower, or absolute watts). */
public enum PowerUnit {
	PCT_FTP, WATTS;

	@JsonValue
	public String wireValue() {
		return name().toLowerCase();
	}

	@JsonCreator
	public static PowerUnit fromWireValue(String value) {
		return PowerUnit.valueOf(value.toUpperCase());
	}
}
