package com.cadence.api.sharing;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum ShareStatus {
	PENDING, ACTIVE;

	@JsonValue
	public String wireValue() {
		return name().toLowerCase();
	}

	@JsonCreator
	public static ShareStatus fromWireValue(String value) {
		return ShareStatus.valueOf(value.toUpperCase());
	}
}
