package com.cadence.api.workouts;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum TargetType {
	POWER, HR, PACE, CADENCE, OPEN;

	@JsonValue
	public String wireValue() {
		return name().toLowerCase();
	}

	@JsonCreator
	public static TargetType fromWireValue(String value) {
		return TargetType.valueOf(value.toUpperCase());
	}
}
