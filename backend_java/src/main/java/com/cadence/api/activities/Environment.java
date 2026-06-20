package com.cadence.api.activities;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** {@code indoor} for treadmill/trainer sessions, which have no GPS track. */
public enum Environment {
	OUTDOOR, INDOOR;

	@JsonValue
	public String wireValue() {
		return name().toLowerCase();
	}

	@JsonCreator
	public static Environment fromWireValue(String value) {
		return Environment.valueOf(value.toUpperCase());
	}
}
