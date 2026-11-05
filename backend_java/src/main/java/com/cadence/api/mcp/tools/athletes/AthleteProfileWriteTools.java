package com.cadence.api.mcp.tools.athletes;

import com.cadence.api.athletes.AthleteService;
import com.cadence.api.athletes.OverlapHandling;
import com.cadence.api.athletes.dto.AthleteUpdateRequest;
import com.cadence.api.athletes.dto.AthleteUpdateResponse;
import com.cadence.api.common.error.ValidationException;
import com.cadence.api.mcp.dispatch.McpScopes;
import com.cadence.api.mcp.dispatch.McpToolAuthorizer;
import com.cadence.api.security.AccessGuard;
import com.cadence.api.users.User;
import com.cadence.api.users.UserService;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/** Calls the exact same {@link AthleteService#updateProfile} the REST {@code AthleteController}
 * does - same pattern as {@code GearWriteTools}/{@code WorkoutWriteTools}. */
@Component
public class AthleteProfileWriteTools {

	private final AthleteService athleteService;
	private final UserService userService;
	private final AthleteProfileTools athleteProfileTools;
	private final AccessGuard accessGuard;
	private final McpToolAuthorizer authorizer;

	public AthleteProfileWriteTools(AthleteService athleteService, UserService userService,
			AthleteProfileTools athleteProfileTools, AccessGuard accessGuard, McpToolAuthorizer authorizer) {
		this.athleteService = athleteService;
		this.userService = userService;
		this.athleteProfileTools = athleteProfileTools;
		this.accessGuard = accessGuard;
		this.authorizer = authorizer;
	}

	@McpTool(name = "set_athlete_thresholds", description = "Set the athlete's FTP, critical "
			+ "running power, and/or threshold pace directly - e.g. from a recent field test, or a "
			+ "coach's assessment. Equivalent to editing these fields in Preferences: only the "
			+ "fields you pass are changed, and each changed value is trusted unconditionally and "
			+ "recorded in the threshold history ledger (it still ages out and can be superseded by "
			+ "a later qualifying activity or another edit, same as any other entry). Pace is "
			+ "\"M:SS\" per km.\n\n"
			+ "effectiveFrom (YYYY-MM-DD, default today) backdates the edit - it's always written "
			+ "as a ledger row, but only becomes the live value if no later row already exists and "
			+ "it isn't already outside thresholdWindowDays as of today (otherwise it's a "
			+ "correction-only row: still used to re-rate past activities, excluded from \"what's "
			+ "current\"). rerateActivities (default true) recomputes intensity/TSS for every "
			+ "activity of the affected sport on/after effectiveFrom. preview=true computes the "
			+ "same outcome (including which activities would be re-rated and their current vs. "
			+ "new intensity/TSS) without saving anything - use it to check the impact before "
			+ "committing.\n\n"
			+ "Returns the athlete's threshold summary (same shape as get_athlete_thresholds) plus "
			+ "thresholdEditResults, keyed by whichever field(s) were passed: changed, "
			+ "becameCurrent, isCorrectionOnly, reratedCount, and wouldRerate (populated only when "
			+ "preview=true).",
			annotations = @McpTool.McpAnnotations(
					readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false))
	public Map<String, Object> setAthleteThresholds(
			@McpToolParam(description = "New FTP in watts, if changing it", required = false) Integer ftp,
			@McpToolParam(description = "New critical running power in watts, if changing it", required = false) Integer criticalRunPower,
			@McpToolParam(description = "New threshold pace as \"M:SS\" per km, if changing it", required = false) String threshold_pace,
			@McpToolParam(description = "Backdate the edit to this date (YYYY-MM-DD), default today", required = false) LocalDate effectiveFrom,
			@McpToolParam(description = "Re-rate affected activities' intensity/TSS, default true", required = false) Boolean rerateActivities,
			@McpToolParam(description = "Compute the outcome without saving anything, default false", required = false) Boolean preview) {
		authorizer.requireScope(McpScopes.ACTIVITIES_WRITE);
		String athleteId = accessGuard.effectiveAthleteId();
		String callerSub = accessGuard.requireWrite(athleteId);
		if (ftp == null && criticalRunPower == null && threshold_pace == null) {
			throw new ValidationException("Pass at least one of ftp, criticalRunPower, threshold_pace.");
		}

		User athlete = userService.getById(athleteId);
		User setBy = callerSub.equals(athleteId) ? athlete : userService.getById(callerSub);
		AthleteUpdateRequest request = new AthleteUpdateRequest(null, null, null, ftp, criticalRunPower, threshold_pace,
				null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
				null, null, null, null, null, null, null, null, null, null, null, null, effectiveFrom, rerateActivities, preview,
				null);
		AthleteService.Outcome outcome = athleteService.updateProfile(athlete, request, setBy);

		Map<String, Object> result = new LinkedHashMap<>();
		result.putAll(athleteProfileTools.thresholdsSummary(athlete));
		result.put("thresholdEditResults", AthleteUpdateResponse.thresholdEditResultsWireMap(outcome.thresholdResults()));
		return result;
	}

	@McpTool(name = "update_import_settings", description = "Set the athlete's overlap_handling "
			+ "default (off/flag/mark/merge) - the default applied whenever an upload or Garmin "
			+ "sync doesn't specify its own onOverlap override (issue #498). merge is accepted but "
			+ "not yet implemented (#501) - it currently behaves like flag.",
			annotations = @McpTool.McpAnnotations(
					readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false))
	public Map<String, Object> updateImportSettings(
			@McpToolParam(description = "off, flag, mark, or merge", required = true) String overlapHandling) {
		authorizer.requireScope(McpScopes.ACTIVITIES_WRITE);
		String athleteId = accessGuard.effectiveAthleteId();
		String callerSub = accessGuard.requireWrite(athleteId);
		OverlapHandling parsed;
		try {
			parsed = OverlapHandling.fromWireValue(overlapHandling);
		}
		catch (IllegalArgumentException e) {
			throw new ValidationException("overlapHandling must be one of: off, flag, mark, merge.");
		}

		User athlete = userService.getById(athleteId);
		User setBy = callerSub.equals(athleteId) ? athlete : userService.getById(callerSub);
		AthleteUpdateRequest request = new AthleteUpdateRequest(
				null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
				null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
				null, null, null, null, parsed);
		athleteService.updateProfile(athlete, request, setBy);

		return Map.of("overlap_handling", athlete.getOverlapHandling());
	}
}
