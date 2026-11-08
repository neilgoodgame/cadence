package com.cadence.api.activities;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Where an {@link ActivitySource}'s stream came from - {@code ATTACHED} for one added via
 * {@code POST .../merge}'s {@code attach} copy mode (issue #503 phase 1). {@code FILE} covers
 * two cases, both from the activity's own single upload rather than a second activity: a former
 * primary snapshotted lazily by {@code ActivitySourceService#setPrimarySource} the first time
 * it's swapped away (phase 1), and - for a run whose FIT file carries both a native and a Stryd
 * power reading - the non-preferred candidate kept at parse time (phase 2, see
 * {@code ActivitySourceService#createParseTimeRunningPowerSource}), so a later preference change
 * can re-designate it instead of only filtering activities out. */
public enum ActivitySourceOrigin {
	FILE, ATTACHED;

	@JsonValue
	public String wireValue() {
		return name().toLowerCase();
	}

	@JsonCreator
	public static ActivitySourceOrigin fromWireValue(String value) {
		return ActivitySourceOrigin.valueOf(value.toUpperCase());
	}
}
