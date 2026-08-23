package com.cadence.api.mcp.tools.workouts;

import com.cadence.api.mcp.dispatch.McpScopes;
import com.cadence.api.mcp.dispatch.McpToolAuthorizer;
import com.cadence.api.security.AccessGuard;
import com.cadence.api.workouts.Workout;
import com.cadence.api.workouts.WorkoutMapper;
import com.cadence.api.workouts.WorkoutService;
import com.cadence.api.workouts.dto.WorkoutDetailResponse;
import com.cadence.api.workouts.dto.WorkoutResponse;
import java.util.List;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * Calls the exact same {@link WorkoutService} the REST {@code WorkoutController} does -
 * {@code WorkoutResponse}/{@code WorkoutDetailResponse} are already compact enough to reuse
 * as-is, unlike {@code ActivityResponse}.
 */
@Component
public class WorkoutReadTools {

	private final WorkoutService workoutService;
	private final WorkoutMapper workoutMapper;
	private final AccessGuard accessGuard;
	private final McpToolAuthorizer authorizer;

	public WorkoutReadTools(WorkoutService workoutService, WorkoutMapper workoutMapper,
			AccessGuard accessGuard, McpToolAuthorizer authorizer) {
		this.workoutService = workoutService;
		this.workoutMapper = workoutMapper;
		this.accessGuard = accessGuard;
		this.authorizer = authorizer;
	}

	@McpTool(name = "list_workouts", description = "List the authenticated athlete's saved "
			+ "workout library entries (structured interval sessions, not completed activities) - "
			+ "optionally filtered by folder, tag, sport, or a name search.",
			annotations = @McpTool.McpAnnotations(
					readOnlyHint = true, destructiveHint = false, idempotentHint = false, openWorldHint = false))
	public List<WorkoutResponse> listWorkouts(
			@McpToolParam(description = "Filter to one folder id", required = false) String folderId,
			@McpToolParam(description = "Filter to workouts with this tag", required = false) String tag,
			@McpToolParam(description = "Filter to one sport: bike or run", required = false) String sport,
			@McpToolParam(description = "Free-text search over workout names", required = false) String search,
			@McpToolParam(description = "Sort order: recent (default), name, duration, tss, or used", required = false) String sort) {
		authorizer.requireScope(McpScopes.ACTIVITIES_READ);
		String athleteId = accessGuard.effectiveAthleteId();
		accessGuard.requireRead(athleteId);
		return workoutService.listWorkouts(athleteId, folderId, tag, sport, search, sort).stream()
				.map(workoutMapper::toResponse).toList();
	}

	@McpTool(name = "get_workout", description = "Get full detail on a single saved workout by id, "
			+ "including its structured interval step tree (warmup/blocks/repeats/cooldown with "
			+ "targets).",
			annotations = @McpTool.McpAnnotations(
					readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
	public WorkoutDetailResponse getWorkout(
			@McpToolParam(description = "The workout id, e.g. wkt_xxxxxxxxxxxx", required = true) String workoutId) {
		authorizer.requireScope(McpScopes.ACTIVITIES_READ);
		Workout workout = workoutService.getWorkoutWithSteps(workoutId);
		accessGuard.requireRead(workout.getCreatedBy().getId());
		var steps = workoutMapper.toStepTree(workout.getSteps());
		return new WorkoutDetailResponse(workoutMapper.toResponse(workout), steps);
	}
}
