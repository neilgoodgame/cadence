package com.cadence.api.activities;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Issue #503 (phase 1): how {@code POST .../merge} copies a field from source onto target.
 * {@code OVERWRITE} (the default, and the only mode before this issue) rewrites the target's
 * own {@link Record} rows in place, per {@code nullPolicy} - {@link
 * ActivityMergeApplyService#applyMergeFields}. {@code ATTACH} doesn't touch the target's
 * existing primary stream at all; it keeps the source's stream alongside as a non-primary {@link
 * ActivitySource}/{@link ActivityStream}, which {@code set_primary_source} can promote later -
 * see {@link ActivitySourceService#attachPowerStream}. Phase 1 only supports {@code ATTACH} for
 * the {@code power} field; any other field with {@code copyMode=ATTACH} is rejected. */
public enum MergeCopyMode {
	OVERWRITE, ATTACH;

	@JsonValue
	public String wireValue() {
		return name().toLowerCase();
	}

	@JsonCreator
	public static MergeCopyMode fromWireValue(String value) {
		return MergeCopyMode.valueOf(value.toUpperCase());
	}
}
