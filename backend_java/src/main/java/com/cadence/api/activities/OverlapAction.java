package com.cadence.api.activities;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** What on-ingest overlap detection (issue #498) actually did about a pair it found, reported
 * back on the {@code Upload} it ran for. {@code MERGED}/{@code MERGE_SKIPPED_LOW_SCORE} are
 * accepted on the wire already but unused until #501 ships a real merge - until then, an
 * {@code OverlapHandling.MERGE} athlete/batch setting always resolves to {@code FLAGGED}. */
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
