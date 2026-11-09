package com.cadence.api.athletes;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Which running-power reading to trust when a FIT file carries both: a watch's own
 * accelerometer-based estimate (NATIVE, e.g. Garmin Running Power) and a third-party footpod's
 * (STRYD). The two commonly disagree substantially - native running-power algorithms tend to
 * read meaningfully higher than Stryd for the same effort - so on {@link com.cadence.api.users.User}
 * this is a deliberate choice, not a fallback preference: the non-selected source isn't used for
 * {@code Record.power}/this activity's own stats, not used when the selected one is momentarily
 * missing (see {@code ParseFileTasklet}'s power-source resolution). Issue #503 (phase 2): the
 * non-selected candidate isn't discarded outright, though - it's kept as a non-primary {@code
 * ActivitySource}/{@code ActivityStream} (see {@code ActivitySourceService
 * #createParseTimeRunningPowerSource}), so a later preference change can re-designate it as
 * primary instead of only filtering activities out. Also reused on {@link
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
