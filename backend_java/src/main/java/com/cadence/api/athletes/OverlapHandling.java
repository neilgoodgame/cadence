package com.cadence.api.athletes;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Issue #498: what, if anything, to do when a newly-ingested activity turns out to overlap in
 * time with one the athlete already has (e.g. the same ride uploaded once from Zwift and once
 * from Garmin). Set per-athlete on {@link com.cadence.api.users.User} (the default used whenever
 * a request doesn't say otherwise - scheduled Garmin syncs in particular never can) and
 * optionally overridden per batch on {@code UploadBatch}.
 *
 * <p>{@code MERGE} is accepted on the wire already (so an athlete who opts into it now needs no
 * further change once merging ships) but is **not yet implemented** - issue #501 - so it
 * currently behaves exactly like {@code FLAG} everywhere it's read. */
public enum OverlapHandling {
	OFF, FLAG, MARK, MERGE;

	@JsonValue
	public String wireValue() {
		return name().toLowerCase();
	}

	@JsonCreator
	public static OverlapHandling fromWireValue(String value) {
		return OverlapHandling.valueOf(value.toUpperCase());
	}
}
