package com.cadence.api.athletes;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Which running-power reading to trust when a FIT file carries both: a watch's own
 * accelerometer-based estimate (NATIVE, e.g. Garmin Running Power) and a third-party footpod's
 * (STRYD). The two commonly disagree substantially - native running-power algorithms tend to
 * read meaningfully higher than Stryd for the same effort - so on {@link com.cadence.api.users.User}
 * this is a deliberate choice, not a fallback preference: the non-selected source is completely
 * ignored at parse time, not used when the selected one is momentarily missing (see
 * {@code ParseFileTasklet}'s power-source resolution). Also reused on {@link
 * com.cadence.api.activities.Activity} (nullable there) to record which source a run's power was
 * actually resolved from - see {@code Activity.getPowerSource()}'s own Javadoc. */
public enum RunningPowerSource {
	STRYD, NATIVE;

	@JsonValue
	public String wireValue() {
		return name().toLowerCase();
	}

	@JsonCreator
	public static RunningPowerSource fromWireValue(String value) {
		return RunningPowerSource.valueOf(value.toUpperCase());
	}
}
