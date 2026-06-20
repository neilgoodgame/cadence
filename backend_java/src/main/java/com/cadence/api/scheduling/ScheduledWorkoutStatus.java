package com.cadence.api.scheduling;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Stored, not derived - except {@code MISSED} is never auto-set by any job; only {@code COMPLETED} ever gets set, by a match or a manual PATCH. */
public enum ScheduledWorkoutStatus {
	PLANNED, COMPLETED, MISSED;

	@JsonValue
	public String wireValue() {
		return name().toLowerCase();
	}

	@JsonCreator
	public static ScheduledWorkoutStatus fromWireValue(String value) {
		return ScheduledWorkoutStatus.valueOf(value.toUpperCase());
	}
}
