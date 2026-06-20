package com.cadence.api.sharing;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** {@code viewer} - read-only access to activities, analysis, workouts, and calendar. {@code coach} - that, plus creating and assigning workouts. */
public enum ShareRole {
	VIEWER, COACH;

	@JsonValue
	public String wireValue() {
		return name().toLowerCase();
	}

	@JsonCreator
	public static ShareRole fromWireValue(String value) {
		return ShareRole.valueOf(value.toUpperCase());
	}
}
