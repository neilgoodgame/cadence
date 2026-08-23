package com.cadence.api.mcp.tools.activities;

import com.cadence.api.activities.Activity;
import com.cadence.api.activities.ActivityService;
import com.cadence.api.activities.LapMapper;
import com.cadence.api.activities.LapRepository;
import com.cadence.api.activities.dto.LapResponse;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.common.error.ValidationException;
import com.cadence.api.mcp.dispatch.McpScopes;
import com.cadence.api.mcp.dispatch.McpToolAuthorizer;
import com.cadence.api.mcp.dto.McpActivityDetail;
import com.cadence.api.mcp.dto.McpActivityPage;
import com.cadence.api.mcp.dto.McpActivitySummary;
import com.cadence.api.security.AccessGuard;
import java.time.LocalDate;
import java.util.List;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * Calls the exact same {@link ActivityService} + {@link AccessGuard} the REST
 * {@code ActivityController} does - the only new things are the scope check and the trimmed
 * response shapes ({@link McpActivitySummary}/{@link McpActivityDetail}), since
 * {@code ActivityResponse} is too wide (~40 fields) for a tool result.
 */
@Component
public class ActivityReadTools {

	private final ActivityService activityService;
	private final LapRepository lapRepository;
	private final LapMapper lapMapper;
	private final AccessGuard accessGuard;
	private final McpToolAuthorizer authorizer;

	public ActivityReadTools(ActivityService activityService, LapRepository lapRepository, LapMapper lapMapper,
			AccessGuard accessGuard, McpToolAuthorizer authorizer) {
		this.activityService = activityService;
		this.lapRepository = lapRepository;
		this.lapMapper = lapMapper;
		this.accessGuard = accessGuard;
		this.authorizer = authorizer;
	}

	@McpTool(name = "list_activities", description = "List the authenticated athlete's activities, "
			+ "most recent first, optionally filtered by sport and/or date range. Returns compact "
			+ "summaries (name, sport, date, duration, distance, avg power/HR, TSS) - use "
			+ "get_activity for the full detail on one activity. Paginated via next_cursor.",
			annotations = @McpTool.McpAnnotations(
					readOnlyHint = true, destructiveHint = false, idempotentHint = false, openWorldHint = false))
	public McpActivityPage listActivities(
			@McpToolParam(description = "Free-text search over activity names", required = false) String query,
			@McpToolParam(description = "Filter to one sport: bike, run, swim, walk, row, multisport, or "
					+ "transition", required = false) String sport,
			@McpToolParam(description = "Only activities on/after this date (YYYY-MM-DD)", required = false) LocalDate after,
			@McpToolParam(description = "Only activities on/before this date (YYYY-MM-DD)", required = false) LocalDate before,
			@McpToolParam(description = "Max results, default 20, capped at 100", required = false) Integer limit,
			@McpToolParam(description = "Pass the previous response's next_cursor to get the next page", required = false) String cursor) {
		authorizer.requireScope(McpScopes.ACTIVITIES_READ);
		String athleteId = accessGuard.effectiveAthleteId();
		accessGuard.requireRead(athleteId);
		int effectiveLimit = Math.max(1, Math.min(limit != null ? limit : 20, 100));
		var page = activityService.list(athleteId, query, parseSport(sport), null, null, after, before, cursor, effectiveLimit);
		return new McpActivityPage(page.hasMore(), page.nextCursor(), page.data().stream().map(McpActivitySummary::from).toList());
	}

	/**
	 * {@code Sport}'s lowercase wire contract ({@code @JsonValue}/{@code @JsonCreator}) isn't
	 * honored by Spring AI's tool-parameter schema generation/binding (confirmed live - it
	 * validates against the raw uppercase enum constant names instead), so enum-typed tool
	 * parameters take a plain String and parse it by hand rather than relying on the framework.
	 */
	private Sport parseSport(String sport) {
		if (sport == null || sport.isBlank()) {
			return null;
		}
		try {
			return Sport.valueOf(sport.trim().toUpperCase());
		} catch (IllegalArgumentException e) {
			throw new ValidationException("sport must be one of: bike, run, swim, walk, row, multisport, transition.", "sport");
		}
	}

	@McpTool(name = "get_activity", description = "Get full detail on a single activity by id "
			+ "(from list_activities' results) - name, sport, duration, distance, power/HR/TSS, "
			+ "elevation, calories, training effect, tags, and linked workout/gear ids.",
			annotations = @McpTool.McpAnnotations(
					readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
	public McpActivityDetail getActivity(
			@McpToolParam(description = "The activity id, e.g. act_xxxxxxxxxxxx", required = true) String activityId) {
		authorizer.requireScope(McpScopes.ACTIVITIES_READ);
		Activity activity = activityService.getActivity(activityId);
		accessGuard.requireRead(activity.getAthlete().getId());
		return McpActivityDetail.from(activityService.toResponse(activity));
	}

	@McpTool(name = "get_activity_laps", description = "Get the lap/interval splits recorded "
			+ "during an activity (index, duration, distance, avg HR/power per lap) - useful for "
			+ "interval workouts where the athlete lapped each rep.",
			annotations = @McpTool.McpAnnotations(
					readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
	public List<LapResponse> getActivityLaps(
			@McpToolParam(description = "The activity id, e.g. act_xxxxxxxxxxxx", required = true) String activityId) {
		authorizer.requireScope(McpScopes.ACTIVITIES_READ);
		Activity activity = activityService.getActivity(activityId);
		accessGuard.requireRead(activity.getAthlete().getId());
		return lapRepository.findByActivityIdOrderByIndex(activityId).stream().map(lapMapper::toResponse).toList();
	}
}
