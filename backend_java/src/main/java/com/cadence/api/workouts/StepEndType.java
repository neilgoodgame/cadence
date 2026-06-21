package com.cadence.api.workouts;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum StepEndType {
	TIME, DISTANCE, MANUAL;

	@JsonValue
	public String wireValue() {
		return name().toLowerCase();
	}

	@JsonCreator
	public static StepEndType fromWireValue(String value) {
		return StepEndType.valueOf(value.toUpperCase());
	}
}
