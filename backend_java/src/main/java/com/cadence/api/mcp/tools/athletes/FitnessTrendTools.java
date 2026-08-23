package com.cadence.api.mcp.tools.athletes;

import com.cadence.api.athletes.FitnessPoint;
import com.cadence.api.athletes.FitnessService;
import com.cadence.api.mcp.dispatch.McpScopes;
import com.cadence.api.mcp.dispatch.McpToolAuthorizer;
import com.cadence.api.security.AccessGuard;
import java.time.LocalDate;
import java.util.List;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/** Calls the exact same {@link FitnessService} the REST {@code AthleteController} does. */
@Component
public class FitnessTrendTools {

	private final FitnessService fitnessService;
	private final AccessGuard accessGuard;
	private final McpToolAuthorizer authorizer;

	public FitnessTrendTools(FitnessService fitnessService, AccessGuard accessGuard, McpToolAuthorizer authorizer) {
		this.fitnessService = fitnessService;
		this.accessGuard = accessGuard;
		this.authorizer = authorizer;
	}

	@McpTool(name = "get_fitness_trend", description = "Get the authenticated athlete's daily "
			+ "CTL (fitness), ATL (fatigue), and TSB (form/freshness) trend over a date range - "
			+ "default is the trailing 84 days. Useful for 'am I overtrained' or 'when's my next "
			+ "good taper window' questions.",
			annotations = @McpTool.McpAnnotations(
					readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
	public List<FitnessPoint> getFitnessTrend(
			@McpToolParam(description = "Start date (YYYY-MM-DD), default 84 days before 'to'", required = false) LocalDate from,
			@McpToolParam(description = "End date (YYYY-MM-DD), default today", required = false) LocalDate to) {
		authorizer.requireScope(McpScopes.ACTIVITIES_READ);
		String athleteId = accessGuard.effectiveAthleteId();
		accessGuard.requireRead(athleteId);
		LocalDate effectiveTo = to != null ? to : LocalDate.now();
		LocalDate effectiveFrom = from != null ? from : effectiveTo.minusDays(84);
		return fitnessService.computeFitnessSeries(athleteId, effectiveFrom, effectiveTo);
	}
}
