package com.cadence.api.activities.dto;

/**
 * One row of {@code GET /v1/activities/overlaps} - issue #498's detection backbone for
 * same-ride-twice duplicates (and #501's merge candidate list). {@code anchor}/{@code other}
 * are arbitrary labels, not "the real one" and "the duplicate" - which side is which only
 * matters when the request pins it via {@code activityId} or {@code device}/{@code otherDevice}.
 *
 * <p>{@code durationDeltaPct} is relative to the anchor's own duration (not an average or the
 * shorter side) - {@code abs(anchor - other) / anchor * 100}. {@code avgPowerRatio} is
 * {@code other / anchor}, null whenever either side has no avg_power.
 */
public record OverlapPairResponse(
		OverlapActivitySummaryResponse anchor,
		OverlapActivitySummaryResponse other,
		long overlapSeconds,
		double overlapPct,
		long startDeltaSeconds,
		Double durationDeltaPct,
		Double avgPowerRatio,
		String status) {
}
