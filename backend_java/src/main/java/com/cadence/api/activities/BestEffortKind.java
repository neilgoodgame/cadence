package com.cadence.api.activities;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum BestEffortKind {
	CYCLING_POWER, RUNNING_PACE, RUNNING_POWER;

	@JsonValue
	public String wireValue() {
		return name().toLowerCase();
	}

	@JsonCreator
	public static BestEffortKind fromWireValue(String value) {
		return BestEffortKind.valueOf(value.toUpperCase());
	}
}
