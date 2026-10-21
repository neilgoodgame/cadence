package com.cadence.api.mcp.tools.races;

import com.cadence.api.athletes.ThresholdSuggestionService;
import com.cadence.api.mcp.McpCustomSplits;
import com.cadence.api.mcp.dispatch.McpScopes;
import com.cadence.api.mcp.dispatch.McpToolAuthorizer;
import com.cadence.api.mcp.dto.McpCustomSplitStepInput;
import com.cadence.api.races.Race;
import com.cadence.api.races.RaceBlockService;
import com.cadence.api.races.RaceService;
import com.cadence.api.races.RaceSplitsService;
import com.cadence.api.races.dto.RaceBlockResponse;
import com.cadence.api.races.dto.RaceCreateRequest;
import com.cadence.api.races.dto.RaceResponse;
import com.cadence.api.security.AccessGuard;
import com.cadence.api.users.User;
import com.cadence.api.users.UserService;
import java.util.List;
import java.util.Map;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/** Calls the exact same {@link RaceService} the REST {@code RaceController} does. */
@Component
public class RaceTools {

	private final RaceService raceService;
	private final RaceBlockService raceBlockService;
	private final RaceSplitsService raceSplitsService;
	private final UserService userService;
	private final ThresholdSuggestionService thresholdSuggestionService;
	private final AccessGuard accessGuard;
	private final McpToolAuthorizer authorizer;

	public RaceTools(RaceService raceService, RaceBlockService raceBlockService, RaceSplitsService raceSplitsService,
			UserService userService, ThresholdSuggestionService thresholdSuggestionService, AccessGuard accessGuard,
			McpToolAuthorizer authorizer) {
		this.raceService = raceService;
		this.raceBlockService = raceBlockService;
		this.raceSplitsService = raceSplitsService;
		this.userService = userService;
		this.thresholdSuggestionService = thresholdSuggestionService;
		this.accessGuard = accessGuard;
		this.authorizer = authorizer;
	}

	@McpTool(name = "list_races", description = "List the authenticated athlete's goal races - "
			+ "name, date, sport, distance, goal/result time, and any notes. Useful for planning "
			+ "training around an upcoming event.",
			annotations = @McpTool.McpAnnotations(
					readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
	public List<RaceResponse> listRaces() {
		authorizer.requireScope(McpScopes.ACTIVITIES_READ);
		String athleteId = accessGuard.effectiveAthleteId();
		accessGuard.requireRead(athleteId);
		return raceService.listRaces(athleteId).stream().map(raceService::toResponse).toList();
	}

	@McpTool(name = "create_race", description = "Create a goal race on the calendar. date is "
			+ "\"YYYY-MM-DD\", goal_time is \"H:MM:SS\". priority is A (the goal race - gets its "
			+ "own training block), B or C (tune-ups, nested inside whichever A race's block they "
			+ "fall in) - defaults to A. For an A race only: block_weeks_requested and base/build/"
			+ "peak/taper_weeks_requested override its training-block length and phase split - the "
			+ "four phase fields must either all be set together (summing exactly to "
			+ "block_weeks_requested) or all be left unset, in which case the block length falls "
			+ "back to the athlete's distance-based default (or their flat preference), and the "
			+ "phase split to a proportional Base/Build/Peak/Taper. These five fields are rejected "
			+ "on a B/C race.",
			annotations = @McpTool.McpAnnotations(
					readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false))
	public RaceResponse createRace(
			@McpToolParam(description = "Race name", required = true) String name,
			@McpToolParam(description = "YYYY-MM-DD", required = true) String date,
			@McpToolParam(description = "bike, run, swim, or multisport", required = false) String sport,
			@McpToolParam(description = "A, B, or C - defaults to A", required = false) String priority,
			@McpToolParam(description = "Race distance in km", required = false) Double distanceKm,
			@McpToolParam(description = "H:MM:SS", required = false) String goalTime,
			@McpToolParam(description = "An existing course to link", required = false) String courseId,
			@McpToolParam(description = "Overrides the athlete-wide \"Key session\" tag lookup for this "
					+ "race's key sessions", required = false) String keySessionTagId,
			@McpToolParam(description = "Notes", required = false) String notes,
			@McpToolParam(description = "Race website URL", required = false) String url,
			@McpToolParam(description = "Target average power in watts", required = false) Integer targetPower,
			@McpToolParam(description = "A races only - explicit training-block length in weeks", required = false) Integer blockWeeksRequested,
			@McpToolParam(description = "A races only - explicit Base-phase length in weeks", required = false) Integer baseWeeksRequested,
			@McpToolParam(description = "A races only - explicit Build-phase length in weeks", required = false) Integer buildWeeksRequested,
			@McpToolParam(description = "A races only - explicit Peak-phase length in weeks", required = false) Integer peakWeeksRequested,
			@McpToolParam(description = "A races only - explicit Taper-phase length in weeks", required = false) Integer taperWeeksRequested) {
		authorizer.requireScope(McpScopes.CALENDAR_WRITE);
		String athleteId = accessGuard.effectiveAthleteId();
		accessGuard.requireWrite(athleteId);
		User athlete = userService.getById(athleteId);

		RaceCreateRequest request = new RaceCreateRequest(name, date, sport, distanceKm, goalTime, null, null, url,
				null, notes != null ? notes : "", priority != null ? priority : "A", courseId, keySessionTagId,
				targetPower, blockWeeksRequested, baseWeeksRequested, buildWeeksRequested, peakWeeksRequested,
				taperWeeksRequested);
		RaceResponse response = raceService.toResponse(raceService.createRace(athlete, request));
		thresholdSuggestionService.invalidate(athleteId);
		return response;
	}

	@McpTool(name = "get_race_block", description = "Get an A race's training block (weekly load/"
			+ "distance, race-day form, predicted time, key sessions) or a B/C race's 5-week window "
			+ "around it.",
			annotations = @McpTool.McpAnnotations(
					readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
	public Object getRaceBlock(String raceId) {
		authorizer.requireScope(McpScopes.ACTIVITIES_READ);
		String athleteId = accessGuard.effectiveAthleteId();
		accessGuard.requireRead(athleteId);

		Race race;
		try {
			race = raceService.getRace(raceId);
		}
		catch (com.cadence.api.common.error.NotFoundException e) {
			return Map.of("error", "not_found");
		}
		if (!race.getAthlete().getId().equals(athleteId)) {
			return Map.of("error", "not_found");
		}
		return raceBlockService.getRaceBlock(race);
	}

	@McpTool(name = "get_race_custom_splits", description = "Get splits for a goal race's linked "
			+ "activity from your own list of steps instead of a fixed distance - e.g. \"the "
			+ "first mile, then every 2km after that\", or steps that mirror a workout's own "
			+ "structure. Each step is either end_type=time (duration_s seconds) or "
			+ "end_type=distance (distance a number with a unit, e.g. \"1mi\"/\"2km\"/\"800m\") - "
			+ "add a label to name one (e.g. \"Mile 1\"). Steps are consumed in order from the "
			+ "start of the recording, each picking up where the previous one left off, so they "
			+ "can never overlap or be out of order - a step with no recording left to consume "
			+ "(the activity ended early) is just dropped, and any recording left over after the "
			+ "last step becomes one unlabeled trailing split. Each split's delta is against the "
			+ "race's own goal pace/target power (or its activity's average, if there's no "
			+ "goal/target) - see get_activity_custom_splits for a plain activity with nothing to "
			+ "compare against.",
			annotations = @McpTool.McpAnnotations(
					readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
	public Object getRaceCustomSplits(
			@McpToolParam(description = "The race id", required = true) String raceId,
			@McpToolParam(description = "The list of steps", required = true) List<McpCustomSplitStepInput> steps) {
		authorizer.requireScope(McpScopes.ACTIVITIES_READ);
		String athleteId = accessGuard.effectiveAthleteId();
		accessGuard.requireRead(athleteId);

		Race race;
		try {
			race = raceService.getRace(raceId);
		}
		catch (com.cadence.api.common.error.NotFoundException e) {
			return Map.of("error", "not_found");
		}
		if (!race.getAthlete().getId().equals(athleteId)) {
			return Map.of("error", "not_found");
		}
		return raceSplitsService.customSplits(race, McpCustomSplits.convert(steps));
	}
}
