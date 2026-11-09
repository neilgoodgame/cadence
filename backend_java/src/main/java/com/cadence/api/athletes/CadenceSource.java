package com.cadence.api.athletes;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Issue #503 (phase 3): which cadence reading to trust as primary when two devices recorded
 * the same ride/run - a pedal-based sensor (PEDALS), a smart trainer's own estimate (TRAINER), or
 * a watch's accelerometer-based estimate (WATCH). Same caveat as {@link HrSource}: there's no
 * device-string classification equivalent - a FIT file's own cadence field carries no record of
 * which physical sensor fed it, so this preference only ever matters when the athlete manually
 * tags a stream's sourceKind at attach time (see {@code ActivitySourceService#attachStream}).
 * {@code ANY} (the default) means no preference - attach never auto-swaps on cadence. Also
 * reused on {@link com.cadence.api.activities.Activity} (nullable there) to record which tagged
 * source a cadence stream was actually attached/swapped from, if any. */
public enum CadenceSource {
	PEDALS, TRAINER, WATCH, ANY;

	@JsonValue
	public String wireValue() {
		return name().toLowerCase();
	}

	@JsonCreator
	public static CadenceSource fromWireValue(String value) {
		return CadenceSource.valueOf(value.toUpperCase());
	}
}
