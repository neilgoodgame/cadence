package com.cadence.api.mcp.tools.races;

import com.cadence.api.mcp.dispatch.McpScopes;
import com.cadence.api.mcp.dispatch.McpToolAuthorizer;
import com.cadence.api.races.RaceService;
import com.cadence.api.races.dto.RaceResponse;
import com.cadence.api.security.AccessGuard;
import java.util.List;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.stereotype.Component;

/** Calls the exact same {@link RaceService} the REST {@code RaceController} does. */
@Component
public class RaceTools {

	private final RaceService raceService;
	private final AccessGuard accessGuard;
	private final McpToolAuthorizer authorizer;

	public RaceTools(RaceService raceService, AccessGuard accessGuard, McpToolAuthorizer authorizer) {
		this.raceService = raceService;
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
}
