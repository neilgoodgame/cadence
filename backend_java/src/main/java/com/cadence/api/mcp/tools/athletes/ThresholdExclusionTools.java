package com.cadence.api.mcp.tools.athletes;

import com.cadence.api.athletes.ThresholdExclusionService;
import com.cadence.api.athletes.ThresholdField;
import com.cadence.api.mcp.dispatch.McpScopes;
import com.cadence.api.mcp.dispatch.McpToolAuthorizer;
import com.cadence.api.security.AccessGuard;
import com.cadence.api.users.User;
import com.cadence.api.users.UserService;
import java.util.List;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/** Issue #616: MCP tools over the same {@link ThresholdExclusionService} the REST
 * {@code ThresholdExclusionController} calls. Unlike the REST POST, preview defaults to true, so an
 * assistant sees the effect before anything is saved. Mirrors Django's AthleteMCPTools
 * exclude_threshold_candidate / include_threshold_candidate / list_threshold_exclusions. */
@Component
public class ThresholdExclusionTools {

	private final ThresholdExclusionService exclusionService;
	private final UserService userService;
	private final AccessGuard accessGuard;
	private final McpToolAuthorizer authorizer;

	public ThresholdExclusionTools(ThresholdExclusionService exclusionService, UserService userService,
			AccessGuard accessGuard, McpToolAuthorizer authorizer) {
		this.exclusionService = exclusionService;
		this.userService = userService;
		this.accessGuard = accessGuard;
		this.authorizer = authorizer;
	}

	@McpTool(name = "list_threshold_exclusions", description = "List the activities excluded from "
			+ "threshold detection (issue #616), oldest first - optionally only one field's (ftp, "
			+ "critical_run_power or threshold_pace), or only one activity's. Each entry has the "
			+ "field, activityId, activityName, activityDate, setByUserId and createdAt. Exclude or "
			+ "re-include an activity with exclude_threshold_candidate / include_threshold_candidate.",
			annotations = @McpTool.McpAnnotations(
					readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
	public List<ThresholdExclusionService.Exclusion> listThresholdExclusions(
			@McpToolParam(description = "Only this field's exclusions: ftp, critical_run_power, or threshold_pace",
					required = false) String field,
			@McpToolParam(description = "Only this activity's exclusions", required = false) String activityId) {
		authorizer.requireScope(McpScopes.ACTIVITIES_READ);
		String athleteId = accessGuard.effectiveAthleteId();
		accessGuard.requireRead(athleteId);
		User athlete = userService.getById(athleteId);
		return exclusionService.list(athlete, field != null ? ThresholdExclusionService.parseField(field) : null,
				activityId);
	}

	@McpTool(name = "exclude_threshold_candidate", description = "Exclude one activity from "
			+ "threshold detection for one field (issue #616) - e.g. a ride recorded on an "
			+ "uncalibrated power meter whose implied FTP is wrong. The activity is then never a "
			+ "candidate for `field` (ftp, critical_run_power or threshold_pace; it must be of that "
			+ "field's sport), it can't define a ledger row, and the field's threshold history is "
			+ "rebuilt from the activity's date, re-rating TSS/intensity from there. Any explicit "
			+ "acceptance of the same activity for `field` is removed. The activity still counts "
			+ "toward training load; with threshold decay on, its decay load uses its heart rate "
			+ "instead of its power (so it adds none if the athlete has no LTHR). Exclusion is per "
			+ "field - excluding a ride for ftp doesn't affect any other field.\n\n"
			+ "preview defaults to TRUE: nothing is saved, and the result shows what would change - "
			+ "valueBefore/valueAfter (the field's live value) and rowsRemoved/rowsAdded (the "
			+ "threshold-history rows from the activity's date on that the rebuild would drop or "
			+ "create). Check the preview, then call again with preview=false to apply it. Excluding "
			+ "an already-excluded activity changes nothing. Undo with include_threshold_candidate.",
			annotations = @McpTool.McpAnnotations(
					readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false))
	public ThresholdExclusionService.Effect excludeThresholdCandidate(
			@McpToolParam(description = "ftp, critical_run_power, or threshold_pace", required = true) String field,
			@McpToolParam(description = "The activity to exclude", required = true) String activityId,
			@McpToolParam(description = "Show the effect without saving anything, default true",
					required = false) Boolean preview) {
		authorizer.requireScope(McpScopes.ACTIVITIES_WRITE);
		String athleteId = accessGuard.effectiveAthleteId();
		String callerSub = accessGuard.requireWrite(athleteId);
		ThresholdField parsed = ThresholdExclusionService.parseField(field);
		User athlete = userService.getById(athleteId);
		User setBy = callerSub.equals(athleteId) ? athlete : userService.getById(callerSub);
		return exclusionService.exclude(athlete, parsed, activityId, setBy, !Boolean.FALSE.equals(preview));
	}

	@McpTool(name = "include_threshold_candidate", description = "Undo exclude_threshold_candidate "
			+ "(issue #616): the activity counts again for `field`'s threshold detection, and the "
			+ "field's threshold history is rebuilt from the activity's date. Fails if the activity "
			+ "isn't excluded for `field`. preview defaults to TRUE, with the same result shape as "
			+ "exclude_threshold_candidate - call again with preview=false to apply it.",
			annotations = @McpTool.McpAnnotations(
					readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false))
	public ThresholdExclusionService.Effect includeThresholdCandidate(
			@McpToolParam(description = "ftp, critical_run_power, or threshold_pace", required = true) String field,
			@McpToolParam(description = "The excluded activity to count again", required = true) String activityId,
			@McpToolParam(description = "Show the effect without saving anything, default true",
					required = false) Boolean preview) {
		authorizer.requireScope(McpScopes.ACTIVITIES_WRITE);
		String athleteId = accessGuard.effectiveAthleteId();
		accessGuard.requireWrite(athleteId);
		ThresholdField parsed = ThresholdExclusionService.parseField(field);
		User athlete = userService.getById(athleteId);
		return exclusionService.include(athlete, parsed, activityId, !Boolean.FALSE.equals(preview));
	}
}
