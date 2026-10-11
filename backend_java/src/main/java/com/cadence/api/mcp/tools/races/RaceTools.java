package com.cadence.api.mcp.tools.races;

import com.cadence.api.mcp.dispatch.McpScopes;
import com.cadence.api.mcp.dispatch.McpToolAuthorizer;
import com.cadence.api.races.Race;
import com.cadence.api.races.RaceBlockService;
import com.cadence.api.races.RaceService;
import com.cadence.api.races.dto.RaceBlockResponse;
import com.cadence.api.races.dto.RaceResponse;
import com.cadence.api.security.AccessGuard;
import java.util.List;
import java.util.Map;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.stereotype.Component;

/** Calls the exact same {@link RaceService} the REST {@code RaceController} does. */
@Component
public class RaceTools {

	private final RaceService raceService;
	private final RaceBlockService raceBlockService;
	private final AccessGuard accessGuard;
	private final McpToolAuthorizer authorizer;

	public RaceTools(RaceService raceService, RaceBlockService raceBlockService, AccessGuard accessGuard,
			McpToolAuthorizer authorizer) {
		this.raceService = raceService;
		this.raceBlockService = raceBlockService;
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
}
