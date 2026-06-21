package com.cadence.api.uploads;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum OnDuplicate {
	SKIP, REPLACE;

	@JsonValue
	public String wireValue() {
		return name().toLowerCase();
	}

	@JsonCreator
	public static OnDuplicate fromWireValue(String value) {
		return OnDuplicate.valueOf(value.toUpperCase());
	}
}
