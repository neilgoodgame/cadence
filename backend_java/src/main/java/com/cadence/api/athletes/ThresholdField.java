package com.cadence.api.athletes;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum ThresholdField {
	FTP, CRITICAL_RUN_POWER, THRESHOLD_PACE;

	@JsonValue
	public String wireValue() {
		return name().toLowerCase();
	}

	@JsonCreator
	public static ThresholdField fromWireValue(String value) {
		return ThresholdField.valueOf(value.toUpperCase());
	}
}
