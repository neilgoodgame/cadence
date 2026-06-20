package com.cadence.api.workouts;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum StepKind {
	WARMUP, BLOCK, REC, COOL;

	@JsonValue
	public String wireValue() {
		return name().toLowerCase();
	}

	@JsonCreator
	public static StepKind fromWireValue(String value) {
		return StepKind.valueOf(value.toUpperCase());
	}
}
