package com.cadence.api.athletes;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum ZoneType {
	HEART_RATE, BIKE_POWER, RUN_POWER, PACE;

	@JsonValue
	public String wireValue() {
		return name().toLowerCase();
	}

	@JsonCreator
	public static ZoneType fromWireValue(String value) {
		return ZoneType.valueOf(value.toUpperCase());
	}
}
