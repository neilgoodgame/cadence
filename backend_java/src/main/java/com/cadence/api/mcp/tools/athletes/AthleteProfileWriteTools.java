package com.cadence.api.mcp.tools.athletes;

import com.cadence.api.athletes.AthleteService;
import com.cadence.api.athletes.BikePowerSource;
import com.cadence.api.athletes.FtpCalculationMethod;
import com.cadence.api.athletes.OverlapHandling;
import com.cadence.api.athletes.RunningPowerSource;
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
				null, null, null, null, null, null, null, null, null, null);
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
				null, null, null, null, parsed, null, null, null, null, null, null, null, null, null);
		athleteService.updateProfile(athlete, request, setBy);

		return Map.of("overlap_handling", athlete.getOverlapHandling());
	}

	@McpTool(name = "update_threshold_settings", description = "Change the athlete's "
			+ "threshold-related settings (issue #499) - the same ones get_threshold_settings "
			+ "returns, and the same as editing them in Preferences. Only the settings you pass are "
			+ "changed. thresholdWindowDays (1-365) and thresholdSanityPct (1-100) drive the "
			+ "rolling-window algorithm; ftpCalculationMethod is twenty_min_test or "
			+ "sixty_min_direct; runningPowerSource is stryd or native; bikePowerSource is pedals, "
			+ "trainer or any. thresholdDecayEnabled turns gradual threshold decay on or off, "
			+ "tuned by thresholdDecayTauDays (7-90), thresholdDecayMinPct (0-10) and "
			+ "thresholdDecayMaxPct (0-30, not below minPct). Changing a setting doesn't rewrite "
			+ "the existing threshold history - it applies from the next recompute, or rebuild the "
			+ "history to re-derive it under the new settings. Use preview_threshold_decay to try "
			+ "decay parameters before saving them. Returns the full settings, as "
			+ "get_threshold_settings does.",
			annotations = @McpTool.McpAnnotations(
					readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false))
	public Map<String, Object> updateThresholdSettings(
			@McpToolParam(description = "Trailing window in days, 1-365", required = false) Integer thresholdWindowDays,
			@McpToolParam(description = "Sanity-band width in percent, 1-100", required = false) Integer thresholdSanityPct,
			@McpToolParam(description = "twenty_min_test or sixty_min_direct", required = false) String ftpCalculationMethod,
			@McpToolParam(description = "stryd or native", required = false) String runningPowerSource,
			@McpToolParam(description = "pedals, trainer, or any", required = false) String bikePowerSource,
			@McpToolParam(description = "Turn gradual threshold decay on or off", required = false) Boolean thresholdDecayEnabled,
			@McpToolParam(description = "Decay time constant in days, 7-90", required = false) Integer thresholdDecayTauDays,
			@McpToolParam(description = "Minimum decay amplitude in percent, 0-10", required = false) Integer thresholdDecayMinPct,
			@McpToolParam(description = "Maximum decay amplitude in percent, 0-30", required = false) Integer thresholdDecayMaxPct) {
		authorizer.requireScope(McpScopes.ACTIVITIES_WRITE);
		String athleteId = accessGuard.effectiveAthleteId();
		String callerSub = accessGuard.requireWrite(athleteId);
		if (thresholdWindowDays == null && thresholdSanityPct == null && ftpCalculationMethod == null
				&& runningPowerSource == null && bikePowerSource == null && thresholdDecayEnabled == null
				&& thresholdDecayTauDays == null && thresholdDecayMinPct == null && thresholdDecayMaxPct == null) {
			throw new ValidationException("Pass at least one setting to change.");
		}
		// Every input is checked here, before anything is applied, so a bad value never leaves
		// earlier settings half-changed on the athlete object. The REST PATCH clamps window and
		// sanity into range; this rejects them instead, matching the Django backend's MCP tool
		// (its serializer rejects out-of-range values).
		requireRange(thresholdWindowDays, 1, 365, "thresholdWindowDays");
		requireRange(thresholdSanityPct, 1, 100, "thresholdSanityPct");
		requireRange(thresholdDecayTauDays, 7, 90, "thresholdDecayTauDays");
		requireRange(thresholdDecayMinPct, 0, 10, "thresholdDecayMinPct");
		requireRange(thresholdDecayMaxPct, 0, 30, "thresholdDecayMaxPct");
		FtpCalculationMethod method = parseEnum(ftpCalculationMethod, FtpCalculationMethod::fromWireValue,
				FtpCalculationMethod::wireValue, "ftpCalculationMethod", "twenty_min_test, sixty_min_direct");
		RunningPowerSource runningSource = parseEnum(runningPowerSource, RunningPowerSource::fromWireValue,
				RunningPowerSource::wireValue, "runningPowerSource", "stryd, native");
		BikePowerSource bikeSource = parseEnum(bikePowerSource, BikePowerSource::fromWireValue,
				BikePowerSource::wireValue, "bikePowerSource", "pedals, trainer, any");

		User athlete = userService.getById(athleteId);
		// min <= max is checked against the merged result (this call's value if passed, else the
		// stored one), like AthleteService.updateProfile does, since a call can pass only one.
		int mergedMin = thresholdDecayMinPct != null ? thresholdDecayMinPct : athlete.getThresholdDecayMinPct();
		int mergedMax = thresholdDecayMaxPct != null ? thresholdDecayMaxPct : athlete.getThresholdDecayMaxPct();
		if ((thresholdDecayMinPct != null || thresholdDecayMaxPct != null) && mergedMin > mergedMax) {
			String field = thresholdDecayMaxPct != null && thresholdDecayMinPct == null
					? "thresholdDecayMaxPct" : "thresholdDecayMinPct";
			throw new ValidationException("thresholdDecayMinPct must not be greater than thresholdDecayMaxPct.", field);
		}
		User setBy = callerSub.equals(athleteId) ? athlete : userService.getById(callerSub);
		athleteService.updateProfile(athlete, AthleteUpdateRequest.thresholdSettings(thresholdWindowDays,
				thresholdSanityPct, method, runningSource, bikeSource, thresholdDecayEnabled, thresholdDecayTauDays,
				thresholdDecayMinPct, thresholdDecayMaxPct), setBy);

		return athleteProfileTools.thresholdSettingsOf(athlete);
	}

	private static void requireRange(Integer value, int min, int max, String field) {
		if (value != null && (value < min || value > max)) {
			throw new ValidationException("Must be between " + min + " and " + max + ".", field);
		}
	}

	/** Accepts only the exact lowercase wire value - fromWireValue alone also takes "PEDALS" or
	 * "Sixty_Min_Direct", which the Django backend's tool rejects. */
	private static <T> T parseEnum(String value, java.util.function.Function<String, T> parser,
			java.util.function.Function<T, String> wire, String field, String allowed) {
		if (value == null) {
			return null;
		}
		T parsed;
		try {
			parsed = parser.apply(value);
		}
		catch (IllegalArgumentException e) {
			parsed = null;
		}
		if (parsed == null || !wire.apply(parsed).equals(value)) {
			throw new ValidationException("Must be one of: " + allowed + ".", field);
		}
		return parsed;
	}
}
