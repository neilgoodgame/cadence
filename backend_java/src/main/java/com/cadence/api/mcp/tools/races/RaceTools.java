package com.cadence.api.mcp.tools.races;

import com.cadence.api.mcp.McpCustomSplits;
import com.cadence.api.mcp.dispatch.McpScopes;
import com.cadence.api.mcp.dispatch.McpToolAuthorizer;
import com.cadence.api.mcp.dto.McpCustomSplitStepInput;
import com.cadence.api.races.Race;
import com.cadence.api.races.RaceBlockService;
import com.cadence.api.races.RaceService;
import com.cadence.api.races.RaceSplitsService;
import com.cadence.api.races.dto.RaceBlockResponse;
import com.cadence.api.races.dto.RaceResponse;
import com.cadence.api.security.AccessGuard;
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
	private final AccessGuard accessGuard;
	private final McpToolAuthorizer authorizer;

	public RaceTools(RaceService raceService, RaceBlockService raceBlockService, RaceSplitsService raceSplitsService,
			AccessGuard accessGuard, McpToolAuthorizer authorizer) {
		this.raceService = raceService;
		this.raceBlockService = raceBlockService;
		this.raceSplitsService = raceSplitsService;
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
