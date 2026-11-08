package com.cadence.api.athletes;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Issue #503 (phase 3): which heartrate reading to trust as primary when two devices recorded
 * the same activity - a chest strap (STRAP, e.g. a TICKR) vs a wrist optical sensor (WRIST, e.g.
 * an Epix). Unlike {@link BikePowerSource}, there's no device-string {@code classify()}
 * equivalent here - a FIT file's own heart_rate field carries no record of which physical sensor
 * fed it (the recording device already arbitrates wrist-vs-strap in firmware before the file is
 * written), so this preference only ever matters when the athlete manually tags a stream's
 * sourceKind at attach time (see {@code ActivitySourceService#attachStream}). {@code ANY} (the
 * default) means no preference - attach never auto-swaps on heartrate. Also reused on {@link
 * com.cadence.api.activities.Activity} (nullable there) to record which tagged source a
 * heartrate stream was actually attached/swapped from, if any. */
public enum HrSource {
	STRAP, WRIST, ANY;

	@JsonValue
	public String wireValue() {
		return name().toLowerCase();
	}

	@JsonCreator
	public static HrSource fromWireValue(String value) {
		return HrSource.valueOf(value.toUpperCase());
	}
}
