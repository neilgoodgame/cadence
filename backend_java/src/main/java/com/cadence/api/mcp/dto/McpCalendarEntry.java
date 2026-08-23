package com.cadence.api.mcp.dto;

import com.cadence.api.scheduling.dto.ScheduledWorkoutResponse;
import java.util.List;

/** Same shape as {@code CalendarResponse}, with {@link McpActivitySummary} embedded for
 * {@code unplannedActivities} instead of the full {@code ActivityResponse}. */
public record McpCalendarEntry(List<ScheduledWorkoutResponse> scheduled, List<McpActivitySummary> unplannedActivities) {
}
