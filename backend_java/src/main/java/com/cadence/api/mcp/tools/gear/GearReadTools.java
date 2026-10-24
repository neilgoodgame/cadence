package com.cadence.api.mcp.tools.gear;

import com.cadence.api.gear.GearService;
import com.cadence.api.gear.ShoeCatalogService;
import com.cadence.api.gear.ShoeService;
import com.cadence.api.gear.dto.BikeResponse;
import com.cadence.api.gear.dto.ShoeCatalogEntryResponse;
import com.cadence.api.gear.dto.ShoeResponse;
import com.cadence.api.mcp.dispatch.McpScopes;
import com.cadence.api.mcp.dispatch.McpToolAuthorizer;
import com.cadence.api.security.AccessGuard;
import java.util.List;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/** Calls the exact same {@link GearService}/{@link ShoeService}/{@link ShoeCatalogService} the
 * REST controllers do. */
@Component
public class GearReadTools {

	private final GearService gearService;
	private final ShoeService shoeService;
	private final ShoeCatalogService shoeCatalogService;
	private final AccessGuard accessGuard;
	private final McpToolAuthorizer authorizer;

	public GearReadTools(GearService gearService, ShoeService shoeService, ShoeCatalogService shoeCatalogService,
			AccessGuard accessGuard, McpToolAuthorizer authorizer) {
		this.gearService = gearService;
		this.shoeService = shoeService;
		this.shoeCatalogService = shoeCatalogService;
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

	@McpTool(name = "search_shoe_catalog", description = "Search the shared shoe catalog "
			+ "(manufacturer + model + version) for a shoeModelVersionId to pass to create_shoe. "
			+ "The catalog is shared across every athlete - if nothing matches, use "
			+ "create_shoe_catalog_entry to add a new one first.",
			annotations = @McpTool.McpAnnotations(
					readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
	public List<ShoeCatalogEntryResponse> searchShoeCatalog(
			@McpToolParam(description = "Free-text search over manufacturer and model; omit to list the whole catalog", required = false) String q) {
		authorizer.requireScope(McpScopes.ACTIVITIES_READ);
		return shoeCatalogService.search(q);
	}
}
