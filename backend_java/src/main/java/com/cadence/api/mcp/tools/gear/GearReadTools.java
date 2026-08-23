package com.cadence.api.mcp.tools.gear;

import com.cadence.api.gear.GearService;
import com.cadence.api.gear.ShoeService;
import com.cadence.api.gear.dto.BikeResponse;
import com.cadence.api.gear.dto.ShoeResponse;
import com.cadence.api.mcp.dispatch.McpScopes;
import com.cadence.api.mcp.dispatch.McpToolAuthorizer;
import com.cadence.api.security.AccessGuard;
import java.util.List;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.stereotype.Component;

/** Calls the exact same {@link GearService}/{@link ShoeService} the REST controllers do. */
@Component
public class GearReadTools {

	private final GearService gearService;
	private final ShoeService shoeService;
	private final AccessGuard accessGuard;
	private final McpToolAuthorizer authorizer;

	public GearReadTools(GearService gearService, ShoeService shoeService, AccessGuard accessGuard, McpToolAuthorizer authorizer) {
		this.gearService = gearService;
		this.shoeService = shoeService;
		this.accessGuard = accessGuard;
		this.authorizer = authorizer;
	}

	@McpTool(name = "list_bikes", description = "List the authenticated athlete's bikes - name, "
			+ "kind, groupset, and lifetime distance/hours/rides. Useful for correlating an "
			+ "activity's power data with which bike was used.",
			annotations = @McpTool.McpAnnotations(
					readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
	public List<BikeResponse> listBikes() {
		authorizer.requireScope(McpScopes.ACTIVITIES_READ);
		String athleteId = accessGuard.effectiveAthleteId();
		accessGuard.requireRead(athleteId);
		return gearService.listBikes(athleteId).stream().map(gearService::toBikeResponse).toList();
	}

	@McpTool(name = "list_shoes", description = "List the authenticated athlete's running shoes - "
			+ "model, role (e.g. daily trainer, race), and distance logged vs. its retirement limit.",
			annotations = @McpTool.McpAnnotations(
					readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
	public List<ShoeResponse> listShoes() {
		authorizer.requireScope(McpScopes.ACTIVITIES_READ);
		String athleteId = accessGuard.effectiveAthleteId();
		accessGuard.requireRead(athleteId);
		return shoeService.listShoes(athleteId).stream().map(shoeService::toResponse).toList();
	}
}
