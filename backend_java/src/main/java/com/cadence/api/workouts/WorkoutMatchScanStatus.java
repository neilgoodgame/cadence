package com.cadence.api.workouts;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum WorkoutMatchScanStatus {
	QUEUED, PROCESSING, READY, FAILED;

	@JsonValue
	public String wireValue() {
		return name().toLowerCase();
	}

	@JsonCreator
	public static WorkoutMatchScanStatus fromWireValue(String value) {
		return WorkoutMatchScanStatus.valueOf(value.toUpperCase());
	}
}
