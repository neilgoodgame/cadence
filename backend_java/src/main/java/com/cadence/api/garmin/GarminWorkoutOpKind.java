package com.cadence.api.garmin;

import com.fasterxml.jackson.annotation.JsonCreator;
import java.util.Locale;

public enum GarminWorkoutOpKind {
	UPSERT_WORKOUT, SCHEDULE, RESCHEDULE, UNSCHEDULE;

	/** The API spells kinds in lower case ({@code upsert_workout}); upper case is accepted too. */
	@JsonCreator
	public static GarminWorkoutOpKind fromJson(String value) {
		try {
			return valueOf(value.trim().toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException | NullPointerException e) {
			throw new IllegalArgumentException("kind must be upsert_workout, schedule, reschedule or unschedule.");
		}
	}
}
