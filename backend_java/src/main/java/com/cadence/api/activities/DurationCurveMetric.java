package com.cadence.api.activities;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum DurationCurveMetric {
	POWER, HEARTRATE;

	@JsonValue
	public String wireValue() {
		return name().toLowerCase();
	}

	@JsonCreator
	public static DurationCurveMetric fromWireValue(String value) {
		return DurationCurveMetric.valueOf(value.toUpperCase());
	}
}
