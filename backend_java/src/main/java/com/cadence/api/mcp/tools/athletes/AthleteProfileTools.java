package com.cadence.api.mcp.tools.athletes;

import com.cadence.api.athletes.ThresholdField;
import com.cadence.api.athletes.ThresholdHistoryCalculator;
import com.cadence.api.athletes.ThresholdHistoryCalculator.CandidateStatusEntry;
import com.cadence.api.athletes.ThresholdHistoryService;
import com.cadence.api.athletes.ZoneService;
import com.cadence.api.athletes.dto.ThresholdHistoryEntryResponse;
import com.cadence.api.athletes.dto.ThresholdSummaryEntry;
import com.cadence.api.athletes.dto.ZoneSetResponse;
import com.cadence.api.common.error.ValidationException;
import com.cadence.api.mcp.dispatch.McpScopes;
import com.cadence.api.mcp.dispatch.McpToolAuthorizer;
import com.cadence.api.mcp.dto.McpAthleteProfile;
import com.cadence.api.security.AccessGuard;
import com.cadence.api.users.User;
import com.cadence.api.users.UserService;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
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
	private final ThresholdHistoryCalculator thresholdHistoryCalculator;
	private final AccessGuard accessGuard;
	private final McpToolAuthorizer authorizer;

	public AthleteProfileTools(UserService userService, ZoneService zoneService,
			ThresholdHistoryService thresholdHistoryService, ThresholdHistoryCalculator thresholdHistoryCalculator,
			AccessGuard accessGuard, McpToolAuthorizer authorizer) {
		this.userService = userService;
		this.zoneService = zoneService;
		this.thresholdHistoryService = thresholdHistoryService;
		this.thresholdHistoryCalculator = thresholdHistoryCalculator;
		this.accessGuard = accessGuard;
		this.authorizer = authorizer;
	}

	private ThresholdField parseField(String field) {
		try {
			return ThresholdField.fromWireValue(field.trim());
		}
		catch (IllegalArgumentException e) {
			throw new ValidationException("field must be one of: ftp, critical_run_power, threshold_pace.", "field");
		}
	}

	@McpTool(name = "get_me", description = "Get the authenticated athlete's profile and training "
			+ "context: age, weight, FTP, critical run power, threshold pace/HR, and whether they "
			+ "coach other athletes. Useful as a first call to establish training-zone context "
			+ "before interpreting other tools' numbers.",
			annotations = @McpTool.McpAnnotations(
					readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
	public McpAthleteProfile getMe() {
		authorizer.requireScope(McpScopes.ACTIVITIES_READ);
		// Deliberately the delegated athlete (effectiveAthleteId), not AuthContextHolder.get().sub()
		// - matches this file's other two tools, and this tool's own docstring promises the
		// training context needed "to interpret other tools' numbers", which are all athlete-scoped
		// too. An earlier version of this tool used sub (the real, possibly-coach principal)
		// instead - correct only while no MCP credential could actually delegate, which stopped
		// being true once PAT/OAuth2 delegation shipped (see AuthContext.java's Javadoc);
		// surfaced live when a virtual coach's get_me came back as its own empty profile instead
		// of the athlete's.
		String athleteId = accessGuard.effectiveAthleteId();
		accessGuard.requireRead(athleteId);
		User user = userService.getById(athleteId);
		return new McpAthleteProfile(
				user.getId(),
				user.getName(),
				user.getAge(),
				user.getWeightKg(),
				user.getFtp(),
				user.getFtpCalculationMethod(),
				user.getCriticalRunPower(),
				user.getRunningPowerSource(),
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
		return thresholdsSummary(athlete);
	}

	@McpTool(name = "list_threshold_history", description = "Get the full threshold-history "
			+ "ledger for one field, most recent first - every measured, manual, or decayed (see "
			+ "kind) entry, not just the current/previous pair get_athlete_thresholds returns. "
			+ "Useful for seeing exactly when the athlete was actually measured (vs. manually set "
			+ "or, once decay ships, eased down), and what a model would have had to predict "
			+ "between those dates. Optionally restricted to entries whose effective date falls "
			+ "within [from, to].",
			annotations = @McpTool.McpAnnotations(
					readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
	public List<ThresholdHistoryEntryResponse> listThresholdHistory(
			@McpToolParam(description = "ftp, critical_run_power, or threshold_pace", required = true) String field,
			@McpToolParam(description = "Only entries effective on/after this date (YYYY-MM-DD)", required = false) LocalDate from,
			@McpToolParam(description = "Only entries effective on/before this date (YYYY-MM-DD)", required = false) LocalDate to) {
		authorizer.requireScope(McpScopes.ACTIVITIES_READ);
		String athleteId = accessGuard.effectiveAthleteId();
		accessGuard.requireRead(athleteId);
		ThresholdField effectiveField = parseField(field);
		User athlete = userService.getById(athleteId);
		return thresholdHistoryService.ledgerFor(athlete, effectiveField, from, to);
	}

	@McpTool(name = "list_threshold_candidates", description = "For every one of the athlete's "
			+ "activities of `field`'s sport between from and to, return the implied value it "
			+ "would contribute to the rolling-window algorithm (e.g. 0.95 x best 20-minute power "
			+ "for ftp), the raw pre-transform value, the window label, and its status: "
			+ "'qualified' (within the sanity band), 'rejected_sanity' (outside it), 'accepted' "
			+ "(explicitly accepted despite failing the band), or 'too_short' (no qualifying "
			+ "effort at all, e.g. too short for the field's window). Classified against the "
			+ "athlete's *current* profile value, a single snapshot rather than a moving "
			+ "reference. Useful for seeing what a model would have had to predict between "
			+ "ledger entries, without reconstructing it by hand from stream summaries.",
			annotations = @McpTool.McpAnnotations(
					readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
	public List<CandidateStatusEntry> listThresholdCandidates(
			@McpToolParam(description = "ftp, critical_run_power, or threshold_pace", required = true) String field,
			@McpToolParam(description = "Start date (YYYY-MM-DD)", required = true) LocalDate from,
			@McpToolParam(description = "End date (YYYY-MM-DD)", required = true) LocalDate to) {
		authorizer.requireScope(McpScopes.ACTIVITIES_READ);
		String athleteId = accessGuard.effectiveAthleteId();
		accessGuard.requireRead(athleteId);
		ThresholdField effectiveField = parseField(field);
		User athlete = userService.getById(athleteId);
		return thresholdHistoryCalculator.candidateStatuses(athlete, effectiveField, from, to);
	}

	@McpTool(name = "get_threshold_settings", description = "Get the authenticated athlete's "
			+ "threshold-related settings: the trailing window (thresholdWindowDays) and "
			+ "sanity-band width (thresholdSanityPct) the rolling-window algorithm uses, and which "
			+ "power-calculation method/source each threshold field is derived from. Useful for "
			+ "knowing which parameters a given ledger was produced under before calibrating "
			+ "anything against it.",
			annotations = @McpTool.McpAnnotations(
					readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
	public Map<String, Object> getThresholdSettings() {
		authorizer.requireScope(McpScopes.ACTIVITIES_READ);
		String athleteId = accessGuard.effectiveAthleteId();
		accessGuard.requireRead(athleteId);
		User athlete = userService.getById(athleteId);
		return Map.of(
				"threshold_window_days", athlete.getThresholdWindowDays(),
				"threshold_sanity_pct", athlete.getThresholdSanityPct(),
				"ftp_calculation_method", athlete.getFtpCalculationMethod(),
				"running_power_source", athlete.getRunningPowerSource());
	}

	// Shared by getAthleteThresholds and AthleteProfileWriteTools.setAthleteThresholds, so both
	// return the identical shape.
	Map<String, ThresholdSummaryEntry> thresholdsSummary(User athlete) {
		return Map.of(
				ThresholdField.FTP.wireValue(), thresholdHistoryService.summaryFor(athlete, ThresholdField.FTP),
				ThresholdField.CRITICAL_RUN_POWER.wireValue(), thresholdHistoryService.summaryFor(athlete, ThresholdField.CRITICAL_RUN_POWER),
				ThresholdField.THRESHOLD_PACE.wireValue(), thresholdHistoryService.summaryFor(athlete, ThresholdField.THRESHOLD_PACE));
	}
}
