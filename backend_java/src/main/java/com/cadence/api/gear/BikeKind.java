package com.cadence.api.gear;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum BikeKind {
	ROAD, INDOOR, GRAVEL, TT;

	@JsonValue
	public String wireValue() {
		return name().toLowerCase();
	}

	@JsonCreator
	public static BikeKind fromWireValue(String value) {
		return BikeKind.valueOf(value.toUpperCase());
	}
}
