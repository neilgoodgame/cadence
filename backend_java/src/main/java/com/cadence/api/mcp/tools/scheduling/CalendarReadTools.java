package com.cadence.api.mcp.tools.scheduling;

import com.cadence.api.activities.ActivityService;
import com.cadence.api.mcp.dispatch.McpScopes;
import com.cadence.api.mcp.dispatch.McpToolAuthorizer;
import com.cadence.api.mcp.dto.McpActivitySummary;
import com.cadence.api.mcp.dto.McpCalendarEntry;
import com.cadence.api.scheduling.SchedulingMapper;
import com.cadence.api.scheduling.SchedulingService;
import com.cadence.api.security.AccessGuard;
import java.time.LocalDate;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * Calls the exact same {@link SchedulingService} the REST {@code CalendarController} does, with
 * {@link McpActivitySummary} embedded for unplanned activities instead of the full
 * {@code ActivityResponse} (see {@link McpCalendarEntry}).
 */
@Component
public class CalendarReadTools {

	private final SchedulingService schedulingService;
	private final SchedulingMapper schedulingMapper;
	private final ActivityService activityService;
	private final AccessGuard accessGuard;
	private final McpToolAuthorizer authorizer;

	public CalendarReadTools(SchedulingService schedulingService, SchedulingMapper schedulingMapper,
			ActivityService activityService, AccessGuard accessGuard, McpToolAuthorizer authorizer) {
		this.schedulingService = schedulingService;
		this.schedulingMapper = schedulingMapper;
		this.activityService = activityService;
		this.accessGuard = accessGuard;
		this.authorizer = authorizer;
	}

	@McpTool(name = "get_calendar", description = "Get the authenticated athlete's calendar for a "
			+ "date range: scheduled (planned) workouts plus completed activities that weren't "
			+ "scheduled or matched to a planned workout. Useful for 'what's on my training plan "
			+ "this week' or 'did I complete everything I planned'.",
			annotations = @McpTool.McpAnnotations(
					readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
	public McpCalendarEntry getCalendar(
			@McpToolParam(description = "Start date (YYYY-MM-DD)", required = true) LocalDate from,
			@McpToolParam(description = "End date (YYYY-MM-DD)", required = true) LocalDate to) {
		authorizer.requireScope(McpScopes.ACTIVITIES_READ);
		String athleteId = accessGuard.effectiveAthleteId();
		accessGuard.requireRead(athleteId);
		var scheduled = schedulingService.getCalendar(athleteId, from, to).stream()
				.map(schedulingMapper::toResponse).toList();
		var unplanned = schedulingService.getUnplannedActivities(athleteId, from, to).stream()
				.map(activityService::toResponse).map(McpActivitySummary::from).toList();
		return new McpCalendarEntry(scheduled, unplanned);
	}
}
