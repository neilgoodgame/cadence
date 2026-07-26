package com.cadence.api.workouts;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum Target2Type {
	CADENCE, NONE;

	@JsonValue
	public String wireValue() {
		return name().toLowerCase();
	}

	@JsonCreator
	public static Target2Type fromWireValue(String value) {
		return Target2Type.valueOf(value.toUpperCase());
	}
}
