package com.cadence.api.gear;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum ServiceAction {
	REPLACED, CLEANED, INSPECTED, ADJUSTED;

	@JsonValue
	public String wireValue() {
		return name().toLowerCase();
	}

	@JsonCreator
	public static ServiceAction fromWireValue(String value) {
		return ServiceAction.valueOf(value.toUpperCase());
	}
}
