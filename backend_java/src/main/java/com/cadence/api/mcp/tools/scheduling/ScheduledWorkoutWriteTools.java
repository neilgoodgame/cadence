package com.cadence.api.mcp.tools.scheduling;

import com.cadence.api.common.error.ValidationException;
import com.cadence.api.mcp.dispatch.McpScopes;
import com.cadence.api.mcp.dispatch.McpToolAuthorizer;
import com.cadence.api.scheduling.SchedulingMapper;
import com.cadence.api.scheduling.SchedulingService;
import com.cadence.api.scheduling.TimeOfDay;
import com.cadence.api.scheduling.dto.ScheduledWorkoutResponse;
import com.cadence.api.security.AccessGuard;
import com.cadence.api.security.AuthContextHolder;
import java.time.LocalDate;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/** Calls the exact same {@link SchedulingService} the REST {@code ScheduledWorkoutController} does. */
@Component
public class ScheduledWorkoutWriteTools {

	private final SchedulingService schedulingService;
	private final SchedulingMapper schedulingMapper;
	private final AccessGuard accessGuard;
	private final McpToolAuthorizer authorizer;

	public ScheduledWorkoutWriteTools(SchedulingService schedulingService, SchedulingMapper schedulingMapper,
			AccessGuard accessGuard, McpToolAuthorizer authorizer) {
		this.schedulingService = schedulingService;
		this.schedulingMapper = schedulingMapper;
		this.accessGuard = accessGuard;
		this.authorizer = authorizer;
	}

	@McpTool(name = "schedule_workout", description = "Put a saved workout (from list_workouts/"
			+ "create_workout) onto the athlete's calendar for a specific date.",
			annotations = @McpTool.McpAnnotations(
					readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false))
	public ScheduledWorkoutResponse scheduleWorkout(
			@McpToolParam(description = "The workout id, e.g. wkt_xxxxxxxxxxxx", required = true) String workoutId,
			@McpToolParam(description = "Date to schedule it on (YYYY-MM-DD)", required = true) LocalDate date,
			@McpToolParam(description = "am, mid, or pm - default mid", required = false) String timeOfDay) {
		authorizer.requireScope(McpScopes.CALENDAR_WRITE);
		String athleteId = accessGuard.effectiveAthleteId();
		accessGuard.requireWrite(athleteId);
		String assignedById = AuthContextHolder.get().sub();
		var scheduled = schedulingService.schedule(assignedById, workoutId, athleteId, date, parseTimeOfDay(timeOfDay));
		return schedulingMapper.toResponse(scheduled);
	}

	private TimeOfDay parseTimeOfDay(String timeOfDay) {
		if (timeOfDay == null || timeOfDay.isBlank()) {
			return TimeOfDay.MID;
		}
		try {
			return TimeOfDay.valueOf(timeOfDay.trim().toUpperCase());
		} catch (IllegalArgumentException e) {
			throw new ValidationException("time_of_day must be one of: am, mid, pm.", "time_of_day");
		}
	}
}
