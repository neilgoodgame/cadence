package com.cadence.api.activities.dto;

import com.cadence.api.common.domain.Sport;
import java.time.Instant;

/** One side of an overlapping-activity pair - see {@link OverlapPairResponse}. */
public record OverlapActivitySummaryResponse(
		String id, String name, Sport sport, String device, Instant startDate, Integer elapsedSeconds, Integer avgPower) {
}
