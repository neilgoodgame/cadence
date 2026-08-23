package com.cadence.api.mcp.dto;

import com.cadence.api.activities.dto.ActivityResponse;
import com.cadence.api.common.domain.Sport;
import java.time.Instant;

/**
 * Trimmed for {@code list_activities} - {@link ActivityResponse} carries ~40 fields (threshold
 * history, multisport/duplicate linkage, environmental data) that add noise without adding value
 * for a list view. {@link McpActivityDetail} carries a few more for the single-activity case.
 */
public record McpActivitySummary(
		String id, String name, Sport sport, Instant startDate, int movingTime, double distanceKm,
		Integer avgPower, Integer avgHr, int tss, Double intensity) {

	public static McpActivitySummary from(ActivityResponse activity) {
		return new McpActivitySummary(
				activity.id(), activity.name(), activity.sport(), activity.startDate(), activity.movingTime(),
				activity.distanceKm(), activity.avgPower(), activity.avgHr(), activity.tss(), activity.intensity());
	}
}
