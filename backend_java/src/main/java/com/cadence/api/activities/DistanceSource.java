package com.cadence.api.activities;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum DistanceSource {
	GPS, FOOTPOD, TRAINER, MANUAL;

	@JsonValue
	public String wireValue() {
		return name().toLowerCase();
	}

	@JsonCreator
	public static DistanceSource fromWireValue(String value) {
		return DistanceSource.valueOf(value.toUpperCase());
	}
}
