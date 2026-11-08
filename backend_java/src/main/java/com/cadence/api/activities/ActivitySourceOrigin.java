package com.cadence.api.activities;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Where an {@link ActivitySource}'s stream came from - {@code FILE} for the activity's own
 * original upload (snapshotted lazily by {@code ActivitySourceService#setPrimarySource} the
 * first time its primary stream is swapped away, so the original is never lost - not created at
 * parse time itself), {@code ATTACHED} for one added via {@code POST .../merge}'s {@code attach}
 * copy mode (issue #503 phase 1). */
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
