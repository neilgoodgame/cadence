package com.cadence.api.mcp.tools.athletes;

import com.cadence.api.athletes.ThresholdField;
import com.cadence.api.athletes.ThresholdHistoryService;
import com.cadence.api.athletes.ZoneService;
import com.cadence.api.athletes.dto.ThresholdSummaryEntry;
import com.cadence.api.athletes.dto.ZoneSetResponse;
import com.cadence.api.mcp.dispatch.McpScopes;
import com.cadence.api.mcp.dispatch.McpToolAuthorizer;
import com.cadence.api.mcp.dto.McpAthleteProfile;
import com.cadence.api.security.AccessGuard;
import com.cadence.api.security.AuthContextHolder;
import com.cadence.api.users.User;
import com.cadence.api.users.UserService;
import java.util.List;
import java.util.Map;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.stereotype.Component;

/**
 * Calls the exact same {@link UserService}/{@link ZoneService}/{@link ThresholdHistoryService}
 * the REST {@code AthleteController} does - the only new things here are the scope check and
 * the trimmed {@link McpAthleteProfile} response shape for {@code get_me}.
 */
@Component
public class AthleteProfileTools {

	private final UserService userService;
	private final ZoneService zoneService;
	private final ThresholdHistoryService thresholdHistoryService;
	private final AccessGuard accessGuard;
	private final McpToolAuthorizer authorizer;

	public AthleteProfileTools(UserService userService, ZoneService zoneService,
			ThresholdHistoryService thresholdHistoryService, AccessGuard accessGuard, McpToolAuthorizer authorizer) {
		this.userService = userService;
		this.zoneService = zoneService;
		this.thresholdHistoryService = thresholdHistoryService;
		this.accessGuard = accessGuard;
		this.authorizer = authorizer;
	}

	@McpTool(name = "get_me", description = "Get the authenticated athlete's profile and training "
			+ "context: age, weight, FTP, critical run power, threshold pace/HR, and whether they "
			+ "coach other athletes. Useful as a first call to establish training-zone context "
			+ "before interpreting other tools' numbers.",
			annotations = @McpTool.McpAnnotations(
					readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
	public McpAthleteProfile getMe() {
		authorizer.requireScope(McpScopes.ACTIVITIES_READ);
		User user = userService.getById(AuthContextHolder.get().sub());
		return new McpAthleteProfile(
				user.getId(),
				user.getName(),
				user.getAge(),
				user.getWeightKg(),
				user.getFtp(),
				user.getFtpCalculationMethod(),
				user.getCriticalRunPower(),
				user.getThresholdPace(),
				user.getLthr(),
				user.getMaxHr(),
				user.getRestingHr(),
				user.isCoach());
	}

	@McpTool(name = "get_athlete_zones", description = "Get the authenticated athlete's training "
			+ "zones (heart rate, bike power, run power, pace) - each zone's name, %-of-threshold "
			+ "range, and the reference value it's computed from.",
			annotations = @McpTool.McpAnnotations(
					readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
	public List<ZoneSetResponse> getAthleteZones() {
		authorizer.requireScope(McpScopes.ACTIVITIES_READ);
		String athleteId = accessGuard.effectiveAthleteId();
		accessGuard.requireRead(athleteId);
		User athlete = userService.getById(athleteId);
		return zoneService.getAllOrCreate(athlete).stream()
				.map(zs -> new ZoneSetResponse(zs.getType(), zoneService.referenceFor(athlete, zs.getType()), zs.getZones()))
				.toList();
	}

	@McpTool(name = "get_athlete_thresholds", description = "Get the authenticated athlete's "
			+ "current FTP, critical run power, and threshold pace - each with its previous value, "
			+ "the activity it was derived from, and whether it's gone stale (source activity aged "
			+ "out of the trailing window).",
			annotations = @McpTool.McpAnnotations(
					readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
	public Map<String, ThresholdSummaryEntry> getAthleteThresholds() {
		authorizer.requireScope(McpScopes.ACTIVITIES_READ);
		String athleteId = accessGuard.effectiveAthleteId();
		accessGuard.requireRead(athleteId);
		User athlete = userService.getById(athleteId);
		return Map.of(
				ThresholdField.FTP.wireValue(), thresholdHistoryService.summaryFor(athlete, ThresholdField.FTP),
				ThresholdField.CRITICAL_RUN_POWER.wireValue(), thresholdHistoryService.summaryFor(athlete, ThresholdField.CRITICAL_RUN_POWER),
				ThresholdField.THRESHOLD_PACE.wireValue(), thresholdHistoryService.summaryFor(athlete, ThresholdField.THRESHOLD_PACE));
	}
}
