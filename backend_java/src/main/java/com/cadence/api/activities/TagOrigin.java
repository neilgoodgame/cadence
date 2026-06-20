package com.cadence.api.activities;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** {@code auto} tags (e.g. "Auto-matched") are server-created and not user-removable. */
public enum TagOrigin {
	MANUAL, AUTO;

	@JsonValue
	public String wireValue() {
		return name().toLowerCase();
	}

	@JsonCreator
	public static TagOrigin fromWireValue(String value) {
		return TagOrigin.valueOf(value.toUpperCase());
	}
}
