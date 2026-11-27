package com.cadence.api.mcp.tools.athletes;

import com.cadence.api.activities.Activity;
import com.cadence.api.activities.ActivityRepository;
import com.cadence.api.athletes.DecayLoadService;
import com.cadence.api.athletes.ThresholdDecay;
import com.cadence.api.athletes.ThresholdDecayRecomputeService;
import com.cadence.api.athletes.ThresholdField;
import com.cadence.api.athletes.ThresholdHistoryCalculator;
import com.cadence.api.athletes.ThresholdHistoryCalculator.CandidateStatusEntry;
import com.cadence.api.athletes.ThresholdHistoryService;
import com.cadence.api.athletes.ZoneService;
import com.cadence.api.athletes.dto.ThresholdHistoryEntryResponse;
import com.cadence.api.athletes.dto.ThresholdSummaryEntry;
import com.cadence.api.athletes.dto.ZoneSetResponse;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.common.error.ValidationException;
import com.cadence.api.mcp.dispatch.McpScopes;
import com.cadence.api.mcp.dispatch.McpToolAuthorizer;
import com.cadence.api.mcp.dto.McpAthleteProfile;
import com.cadence.api.security.AccessGuard;
import com.cadence.api.users.User;
import com.cadence.api.users.UserService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
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
	private final ThresholdDecayRecomputeService thresholdDecayRecomputeService;
	private final DecayLoadService decayLoadService;
	private final ActivityRepository activityRepository;
	private final AccessGuard accessGuard;
	private final McpToolAuthorizer authorizer;

	public AthleteProfileTools(UserService userService, ZoneService zoneService,
			ThresholdHistoryService thresholdHistoryService, ThresholdHistoryCalculator thresholdHistoryCalculator,
			ThresholdDecayRecomputeService thresholdDecayRecomputeService, DecayLoadService decayLoadService,
			ActivityRepository activityRepository, AccessGuard accessGuard, McpToolAuthorizer authorizer) {
		this.userService = userService;
		this.zoneService = zoneService;
		this.thresholdHistoryService = thresholdHistoryService;
		this.thresholdHistoryCalculator = thresholdHistoryCalculator;
		this.thresholdDecayRecomputeService = thresholdDecayRecomputeService;
		this.decayLoadService = decayLoadService;
		this.activityRepository = activityRepository;
		this.accessGuard = accessGuard;
		this.authorizer = authorizer;
	}

	private static Sport sportFor(ThresholdField field) {
		return field == ThresholdField.FTP ? Sport.BIKE : Sport.RUN;
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
			+ "(explicitly accepted despite failing the band), 'excluded' (excluded from threshold "
			+ "detection for this field, so never a candidate - its implied value is still "
			+ "shown), or 'too_short' (no qualifying effort at all, e.g. too short for the "
			+ "field's window). Classified against the "
			+ "athlete's *current* profile value, a single snapshot rather than a moving "
			+ "reference. Useful for seeing what a model would have had to predict between "
			+ "ledger entries, without reconstructing it by hand from stream summaries. "
			+ "includeDuplicates (default false) also classifies same-ride-twice duplicate copies, "
			+ "which are otherwise excluded entirely (issue #498).",
			annotations = @McpTool.McpAnnotations(
					readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
	public List<CandidateStatusEntry> listThresholdCandidates(
			@McpToolParam(description = "ftp, critical_run_power, or threshold_pace", required = true) String field,
			@McpToolParam(description = "Start date (YYYY-MM-DD)", required = true) LocalDate from,
			@McpToolParam(description = "End date (YYYY-MM-DD)", required = true) LocalDate to,
			@McpToolParam(description = "Also classify same-ride-twice duplicate copies, which are "
					+ "excluded by default (default false)", required = false) Boolean includeDuplicates) {
		authorizer.requireScope(McpScopes.ACTIVITIES_READ);
		String athleteId = accessGuard.effectiveAthleteId();
		accessGuard.requireRead(athleteId);
		ThresholdField effectiveField = parseField(field);
		User athlete = userService.getById(athleteId);
		return thresholdHistoryCalculator.candidateStatuses(
				athlete, effectiveField, from, to, Boolean.TRUE.equals(includeDuplicates));
	}

	@McpTool(name = "get_threshold_settings", description = "Get the authenticated athlete's "
			+ "threshold-related settings: the trailing window (thresholdWindowDays) and "
			+ "sanity-band width (thresholdSanityPct) the rolling-window algorithm uses, which "
			+ "power-calculation method/source each threshold field is derived from, and the "
			+ "threshold-decay settings (thresholdDecayEnabled, tauDays, minPct, maxPct). Useful "
			+ "for knowing which parameters a given ledger was produced under before calibrating "
			+ "anything against it. Change them with update_threshold_settings.",
			annotations = @McpTool.McpAnnotations(
					readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
	public Map<String, Object> getThresholdSettings() {
		authorizer.requireScope(McpScopes.ACTIVITIES_READ);
		String athleteId = accessGuard.effectiveAthleteId();
		accessGuard.requireRead(athleteId);
		return thresholdSettingsOf(userService.getById(athleteId));
	}

	/** get_threshold_settings' body, shared with update_threshold_settings' return value. */
	public Map<String, Object> thresholdSettingsOf(User athlete) {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("threshold_window_days", athlete.getThresholdWindowDays());
		result.put("threshold_sanity_pct", athlete.getThresholdSanityPct());
		result.put("ftp_calculation_method", athlete.getFtpCalculationMethod());
		result.put("running_power_source", athlete.getRunningPowerSource());
		result.put("bike_power_source", athlete.getBikePowerSource());
		result.put("threshold_decay_enabled", athlete.isThresholdDecayEnabled());
		result.put("threshold_decay_tau_days", athlete.getThresholdDecayTauDays());
		result.put("threshold_decay_min_pct", athlete.getThresholdDecayMinPct());
		result.put("threshold_decay_max_pct", athlete.getThresholdDecayMaxPct());
		return result;
	}

	@McpTool(name = "preview_threshold_decay", description = "Issue #487: models what the "
			+ "threshold-decay ledger would look like for field between from and to, using "
			+ "tauDays/minPct/maxPct in place of the athlete's own saved decay settings where "
			+ "given (any omitted one falls back to the athlete's saved value) - for calibrating "
			+ "those parameters against real history before turning decay on, or trying "
			+ "alternatives once it already is. Never writes to the ledger, and the simulation "
			+ "always runs as if decay were enabled regardless of the athlete's own "
			+ "thresholdDecayEnabled - otherwise every override here would be silently ignored. "
			+ "settingsUsed reports the tauDays/minPct/maxPct the simulation actually ran with.\n\n"
			+ "data covers the whole [from, to] range, with no row cap. A row appears only when the "
			+ "rounded value changes (measured, manual or decayed), and the value holds until the "
			+ "next row; value_at_to is the value in effect on to. Manual ledger entries in the "
			+ "range appear as kind \"manual\" rows. The simulation starts from the real ledger as "
			+ "it stood the day before from, not from an earlier preview's result, so two adjacent "
			+ "previews can disagree at their boundary when the real ledger differs from the "
			+ "simulated one there (e.g. a value that only exists because decay is simulated).",
			annotations = @McpTool.McpAnnotations(
					readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
	public Map<String, Object> previewThresholdDecay(
			@McpToolParam(description = "ftp, critical_run_power, or threshold_pace", required = true) String field,
			@McpToolParam(description = "Start date (YYYY-MM-DD)", required = true) LocalDate from,
			@McpToolParam(description = "End date (YYYY-MM-DD)", required = true) LocalDate to,
			@McpToolParam(description = "Overrides the athlete's saved tau (days), if given",
					required = false) Double tauDays,
			@McpToolParam(description = "Overrides the athlete's saved min amplitude (%), if given",
					required = false) Double minPct,
			@McpToolParam(description = "Overrides the athlete's saved max amplitude (%), if given",
					required = false) Double maxPct) {
		authorizer.requireScope(McpScopes.ACTIVITIES_READ);
		String athleteId = accessGuard.effectiveAthleteId();
		accessGuard.requireRead(athleteId);
		ThresholdField effectiveField = parseField(field);
		if (from.isAfter(to)) {
			throw new ValidationException("Must not be after to.", "from");
		}
		User athlete = userService.getById(athleteId);
		// Same bounds AthleteService#updateProfile enforces on the saved settings, checked
		// against the effective (override-or-saved) value. Java's own floating-point division
		// never throws on tauDays=0 (IEEE 754 -Infinity, not ArithmeticException, unlike
		// Python's backend) - but silently simulating with it would still be nonsense, and the
		// two backends must reject the same bad input the same way (root CLAUDE.md's parity
		// rule), not just avoid crashing.
		double effectiveTau = tauDays != null ? tauDays : athlete.getThresholdDecayTauDays();
		double effectiveMin = minPct != null ? minPct : athlete.getThresholdDecayMinPct();
		double effectiveMax = maxPct != null ? maxPct : athlete.getThresholdDecayMaxPct();
		if (effectiveTau < 7 || effectiveTau > 90) {
			throw new ValidationException("Must be between 7 and 90.", "tauDays");
		}
		if (effectiveMin < 0 || effectiveMin > 10) {
			throw new ValidationException("Must be between 0 and 10.", "minPct");
		}
		if (effectiveMax < 0 || effectiveMax > 30) {
			throw new ValidationException("Must be between 0 and 30.", "maxPct");
		}
		if (effectiveMin > effectiveMax) {
			throw new ValidationException("Must not exceed maxPct.", "minPct");
		}
		ThresholdDecayRecomputeService.PreviewResult result = thresholdDecayRecomputeService.gatherPreviewSteps(
				athlete, effectiveField, from, to, tauDays, minPct, maxPct);
		ThresholdDecay.DecaySettings settings = result.settingsUsed();
		java.util.function.Function<Double, Object> wire = value -> effectiveField == ThresholdField.THRESHOLD_PACE
				? ThresholdDecayRecomputeService.secondsToMmss(value)
				: (Object) (int) Math.round(value);
		List<ThresholdDecay.Step> steps = result.steps();
		Map<String, Object> response = new LinkedHashMap<>();
		response.put("field", field);
		// null when the preview produced no rows at all (e.g. no ledger to start from).
		response.put("value_at_to", steps.isEmpty() ? null : wire.apply(steps.get(steps.size() - 1).value()));
		response.put("settings_used",
				Map.of("tau_days", settings.tauDays(), "min_pct", settings.minPct(), "max_pct", settings.maxPct()));
		response.put("data", steps.stream().map(step -> {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("value", wire.apply(step.value()));
			row.put("kind", step.kind());
			row.put("activity_id", step.activityId());
			row.put("effective_from", step.effectiveFrom());
			row.put("current_from", step.currentFrom());
			return row;
		}).toList());
		return response;
	}

	@McpTool(name = "list_activity_above_z2_load", description = "Issue #487: for every one of "
			+ "the athlete's activities of field's sport between from and to, the above-Z2 "
			+ "training load DecayLoadService#aboveZ2LoadForActivity computes from its stored "
			+ "intensity histogram - the same per-activity number the threshold-decay load term "
			+ "sums into a 42-day EWMA, exposed directly here for calibrating that load term "
			+ "against real history. reference, if given, overrides every activity's own "
			+ "in-effect threshold (otherwise each uses whatever was actually recorded as "
			+ "current on its own date) - e.g. to ask what this load would have looked like if "
			+ "FTP had been a fixed value the whole time. aboveZ2Load is null for an activity "
			+ "with no usable histogram data, or no reference to measure it against - never a "
			+ "fabricated 0.0. includeDuplicates (default false) also includes same-ride-twice "
			+ "duplicate copies, which are otherwise excluded entirely (issue #498).",
			annotations = @McpTool.McpAnnotations(
					readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
	public Map<String, Object> listActivityAboveZ2Load(
			@McpToolParam(description = "ftp, critical_run_power, or threshold_pace", required = true) String field,
			@McpToolParam(description = "Start date (YYYY-MM-DD)", required = true) LocalDate from,
			@McpToolParam(description = "End date (YYYY-MM-DD)", required = true) LocalDate to,
			@McpToolParam(description = "Overrides every activity's own in-effect threshold, if given",
					required = false) Double reference,
			@McpToolParam(description = "Also include same-ride-twice duplicate copies, default false",
					required = false) Boolean includeDuplicates) {
		authorizer.requireScope(McpScopes.ACTIVITIES_READ);
		String athleteId = accessGuard.effectiveAthleteId();
		accessGuard.requireRead(athleteId);
		ThresholdField effectiveField = parseField(field);
		if (from.isAfter(to)) {
			throw new ValidationException("Must not be after to.", "from");
		}
		User athlete = userService.getById(athleteId);
		Instant start = from.atStartOfDay(ZoneOffset.UTC).toInstant();
		Instant end = to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
		List<Activity> activities = activityRepository.findForThresholdWindow(
				athleteId, sportFor(effectiveField), start, end, Boolean.TRUE.equals(includeDuplicates));

		List<Map<String, Object>> data = activities.stream().map(activity -> {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("activity_id", activity.getId());
			row.put("date", activity.getStartDate().atZone(ZoneOffset.UTC).toLocalDate());
			row.put("above_z2_load",
					decayLoadService.aboveZ2LoadForActivity(activity, athlete, effectiveField, reference));
			return row;
		}).toList();
		return Map.of("field", field, "data", data);
	}

	@McpTool(name = "get_sensor_preferences", description = "Get the authenticated athlete's "
			+ "per-field sensor preferences: running_power_source, bike_power_source, hr_source, "
			+ "cadence_source. Each decides which source becomes primary by default the next time "
			+ "a matching stream is attached (see merge_activities' attach copy_mode/"
			+ "set_primary_source) - 'any'/no preference means attach never auto-swaps for that "
			+ "field. Changing a preference never retroactively re-resolves an already-attached "
			+ "activity's primary stream.",
			annotations = @McpTool.McpAnnotations(
					readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
	public Map<String, Object> getSensorPreferences() {
		authorizer.requireScope(McpScopes.ACTIVITIES_READ);
		String athleteId = accessGuard.effectiveAthleteId();
		accessGuard.requireRead(athleteId);
		User athlete = userService.getById(athleteId);
		return Map.of(
				"running_power_source", athlete.getRunningPowerSource(),
				"bike_power_source", athlete.getBikePowerSource(),
				"hr_source", athlete.getHrSource(),
				"cadence_source", athlete.getCadenceSource());
	}

	@McpTool(name = "get_import_settings", description = "Get the authenticated athlete's "
			+ "duplicate-detection import settings: overlap_handling (off/flag/mark/merge) - the "
			+ "default applied whenever an upload or Garmin sync doesn't specify its own onOverlap "
			+ "override (issue #498). merge is accepted but not yet implemented (#501) - it "
			+ "currently behaves like flag.",
			annotations = @McpTool.McpAnnotations(
					readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
	public Map<String, Object> getImportSettings() {
		authorizer.requireScope(McpScopes.ACTIVITIES_READ);
		String athleteId = accessGuard.effectiveAthleteId();
		accessGuard.requireRead(athleteId);
		User athlete = userService.getById(athleteId);
		return Map.of("overlap_handling", athlete.getOverlapHandling());
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
