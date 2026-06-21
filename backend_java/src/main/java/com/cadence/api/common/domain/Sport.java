package com.cadence.api.common.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Shared across activities and workouts; workouts restrict this to {@code BIKE}/{@code RUN} at the service layer. */
public enum Sport {
	BIKE, RUN, SWIM, WALK;

	@JsonValue
	public String wireValue() {
		return name().toLowerCase();
	}

	@JsonCreator
	public static Sport fromWireValue(String value) {
		return Sport.valueOf(value.toUpperCase());
	}
}
