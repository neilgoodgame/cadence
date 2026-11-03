package com.cadence.api.mcp.tools.athletes;

import com.cadence.api.athletes.FitnessPoint;
import com.cadence.api.athletes.FitnessService;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.common.error.ValidationException;
import com.cadence.api.mcp.dispatch.McpScopes;
import com.cadence.api.mcp.dispatch.McpToolAuthorizer;
import com.cadence.api.security.AccessGuard;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
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

	// Real training sports only - multisport/transition are structural FIT-import artifacts, not
	// something an athlete would ever ask for a fitness trend of.
	private static final Set<Sport> FITNESS_TREND_SPORTS = Set.of(Sport.BIKE, Sport.RUN, Sport.SWIM, Sport.WALK, Sport.ROW);

	@McpTool(name = "get_fitness_trend", description = "Get the authenticated athlete's daily "
			+ "CTL (fitness), ATL (fatigue), and TSB (form/freshness) trend over a date range - "
			+ "default is the trailing 84 days. Useful for 'am I overtrained' or 'when's my next "
			+ "good taper window' questions.",
			annotations = @McpTool.McpAnnotations(
					readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
	public List<FitnessPoint> getFitnessTrend(
			@McpToolParam(description = "Start date (YYYY-MM-DD), default 84 days before 'to'", required = false) LocalDate from,
			@McpToolParam(description = "End date (YYYY-MM-DD), default today", required = false) LocalDate to,
			@McpToolParam(description = "Restrict to one sport (bike, run, swim, walk, row) - e.g. a "
					+ "bike-only CTL to compare against FTP, independent of running load. Default: "
					+ "all sports combined.", required = false) String sport) {
		authorizer.requireScope(McpScopes.ACTIVITIES_READ);
		String athleteId = accessGuard.effectiveAthleteId();
		accessGuard.requireRead(athleteId);
		Sport effectiveSport = parseSport(sport);
		LocalDate effectiveTo = to != null ? to : LocalDate.now();
		LocalDate effectiveFrom = from != null ? from : effectiveTo.minusDays(84);
		return fitnessService.computeFitnessSeries(athleteId, effectiveFrom, effectiveTo, effectiveSport);
	}

	private Sport parseSport(String sport) {
		if (sport == null) {
			return null;
		}
		Sport parsed;
		try {
			parsed = Sport.fromWireValue(sport.trim());
		}
		catch (IllegalArgumentException e) {
			throw new ValidationException("sport must be one of: bike, run, swim, walk, row.", "sport");
		}
		if (!FITNESS_TREND_SPORTS.contains(parsed)) {
			throw new ValidationException("sport must be one of: bike, run, swim, walk, row.", "sport");
		}
		return parsed;
	}
}
