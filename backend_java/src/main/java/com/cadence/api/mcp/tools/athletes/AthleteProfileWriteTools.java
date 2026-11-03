package com.cadence.api.mcp.tools.athletes;

import com.cadence.api.athletes.AthleteService;
import com.cadence.api.athletes.dto.AthleteUpdateRequest;
import com.cadence.api.athletes.dto.ThresholdSummaryEntry;
import com.cadence.api.common.error.ValidationException;
import com.cadence.api.mcp.dispatch.McpScopes;
import com.cadence.api.mcp.dispatch.McpToolAuthorizer;
import com.cadence.api.security.AccessGuard;
import com.cadence.api.users.User;
import com.cadence.api.users.UserService;
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
			+ "\"M:SS\" per km. Returns the athlete's threshold summary (same shape as "
			+ "get_athlete_thresholds) after applying the change.",
			annotations = @McpTool.McpAnnotations(
					readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false))
	public Map<String, ThresholdSummaryEntry> setAthleteThresholds(
			@McpToolParam(description = "New FTP in watts, if changing it", required = false) Integer ftp,
			@McpToolParam(description = "New critical running power in watts, if changing it", required = false) Integer criticalRunPower,
			@McpToolParam(description = "New threshold pace as \"M:SS\" per km, if changing it", required = false) String threshold_pace) {
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
				null, null, null, null, null, null, null, null, null, null, null, null);
		athleteService.updateProfile(athlete, request, setBy);
		return athleteProfileTools.thresholdsSummary(athlete);
	}
}
