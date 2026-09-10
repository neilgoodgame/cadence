package com.cadence.api.athletes;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Governs a *new import's* laps only ({@code WorkoutAutoMatchService.attemptMatch}, right
 * after a match is found) - not retroactive. {@link #MATCHED_WORKOUT} derives laps from the
 * matched Workout's own step boundaries instead of the device's own FIT-file lap markers,
 * since a device lap and a workout step don't reliably line up 1:1 (a trailing "stop
 * recording" lap, a skipped rep, an extra lap press). {@link #ORIGINAL} keeps the device's own
 * laps, unlinked to any workout step - today's behavior. Either way, an already-matched
 * activity's laps can be re-derived on demand via {@code POST /v1/activities/{id}/regenerate-laps}
 * regardless of this preference's current value. */
public enum LapSource {
	MATCHED_WORKOUT, ORIGINAL;

	@JsonValue
	public String wireValue() {
		return name().toLowerCase();
	}

	@JsonCreator
	public static LapSource fromWireValue(String value) {
		return LapSource.valueOf(value.toUpperCase());
	}
}
