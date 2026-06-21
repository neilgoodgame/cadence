package com.cadence.api.scheduling;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum TimeOfDay {
	AM, MID, PM;

	@JsonValue
	public String wireValue() {
		return name();
	}

	@JsonCreator
	public static TimeOfDay fromWireValue(String value) {
		return TimeOfDay.valueOf(value.toUpperCase());
	}
}
