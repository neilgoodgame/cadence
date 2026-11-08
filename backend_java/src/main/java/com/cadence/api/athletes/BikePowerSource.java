package com.cadence.api.athletes;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Issue #499 (generalised into #503's per-field sensor preferences): which bike power reading
 * to trust as primary when two devices recorded the same ride - a pedal-based meter (PEDALS,
 * e.g. Favero Assioma) vs a trainer's own estimate (TRAINER, e.g. a Wahoo Kickr), which commonly
 * reads 2-3.5% lower than the pedals (power is measured after drivetrain losses). {@code ANY}
 * (the default) keeps today's behaviour - no preference applied. Unlike {@link
 * RunningPowerSource}, this doesn't discard the non-preferred stream at parse time: #503 phase 1
 * keeps both as an {@code ActivitySource}/{@code ActivityStream} pair and uses this preference
 * only to decide which one defaults to primary when attached (see {@code
 * ActivityMergeApplyService}'s attach {@code copyMode}) - the athlete can always override with
 * {@code set_primary_source}. Also reused on {@link com.cadence.api.activities.Activity}
 * (nullable there) to record which source a bike activity's primary power stream was actually
 * attached from - see {@code Activity.getBikePowerSource()}'s own Javadoc. */
public enum BikePowerSource {
	PEDALS, TRAINER, ANY;

	@JsonValue
	public String wireValue() {
		return name().toLowerCase();
	}

	@JsonCreator
	public static BikePowerSource fromWireValue(String value) {
		return BikePowerSource.valueOf(value.toUpperCase());
	}
}
