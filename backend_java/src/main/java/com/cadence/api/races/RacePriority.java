package com.cadence.api.races;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** A races are goal races and get a training block; B and C are tune-ups that nest inside
 * the A race whose block they fall in. */
public enum RacePriority {
	A, B, C;

	@JsonValue
	public String wireValue() {
		return name();
	}

	@JsonCreator
	public static RacePriority fromWireValue(String value) {
		return RacePriority.valueOf(value.toUpperCase());
	}
}
