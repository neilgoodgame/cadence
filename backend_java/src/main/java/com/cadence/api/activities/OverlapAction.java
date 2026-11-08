package com.cadence.api.activities;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** What on-ingest overlap detection (issue #498) actually did about a pair it found, reported
 * back on the {@code Upload} it ran for. {@code MERGED} (issue #501 phase 4): the pair was
 * actually merged via the athlete's saved-or-built-in {@link MergeRuleService} rule.
 * {@code MERGE_SKIPPED_LOW_SCORE}: a rule applied but the resolved alignment score didn't clear
 * {@link ActivityOverlapDetectionService#MERGE_ALIGNMENT_THRESHOLD} - detected and reported,
 * nothing written. See {@link ActivityOverlapDetectionService}'s own Javadoc for the full
 * decision. */
public enum OverlapAction {
	FLAGGED, MARKED, MERGED, MERGE_SKIPPED_LOW_SCORE;

	@JsonValue
	public String wireValue() {
		return name().toLowerCase();
	}

	@JsonCreator
	public static OverlapAction fromWireValue(String value) {
		return OverlapAction.valueOf(value.toUpperCase());
	}
}
