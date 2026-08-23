package com.cadence.api.mcp.tools.athletes;

import com.cadence.api.activities.BestEffort;
import com.cadence.api.activities.BestEffortKind;
import com.cadence.api.activities.BestEffortRepository;
import com.cadence.api.athletes.BestEffortController;
import com.cadence.api.athletes.dto.BestEffortListResponse;
import com.cadence.api.athletes.dto.BestEffortResponse;
import com.cadence.api.common.error.ValidationException;
import com.cadence.api.mcp.dispatch.McpScopes;
import com.cadence.api.mcp.dispatch.McpToolAuthorizer;
import com.cadence.api.security.AccessGuard;
import com.cadence.api.users.UserService;
import java.time.LocalDate;
import java.util.List;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * Calls the same {@link BestEffortRepository} query and reuses
 * {@link BestEffortController#capPerWindow} - the same top-N-per-window capping the REST endpoint
 * applies, so the two paths can never disagree on what counts as a "best effort."
 */
@Component
public class BestEffortTools {

	private final BestEffortRepository bestEffortRepository;
	private final UserService userService;
	private final AccessGuard accessGuard;
	private final McpToolAuthorizer authorizer;

	public BestEffortTools(BestEffortRepository bestEffortRepository, UserService userService,
			AccessGuard accessGuard, McpToolAuthorizer authorizer) {
		this.bestEffortRepository = bestEffortRepository;
		this.userService = userService;
		this.accessGuard = accessGuard;
		this.authorizer = authorizer;
	}

	@McpTool(name = "list_best_efforts", description = "Get the authenticated athlete's best "
			+ "efforts for one metric (e.g. best 5-min power ever recorded) across all tracked "
			+ "durations, optionally restricted to a recent period. Useful for 'what's my best "
			+ "20-minute power' or judging whether a recent activity set a new PR.",
			annotations = @McpTool.McpAnnotations(
					readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
	public BestEffortListResponse listBestEfforts(
			@McpToolParam(description = "cycling_hr, cycling_power, running_hr, running_pace, or "
					+ "running_power", required = true) String kind,
			@McpToolParam(description = "all (default), 4w, 3m, 16w, or 1y", required = false) String period) {
		authorizer.requireScope(McpScopes.ACTIVITIES_READ);
		String athleteId = accessGuard.effectiveAthleteId();
		accessGuard.requireRead(athleteId);
		BestEffortKind effectiveKind = parseKind(kind);
		String effectivePeriod = period != null ? period : "all";

		LocalDate since = switch (effectivePeriod) {
			case "4w" -> LocalDate.now().minusDays(28);
			case "3m" -> LocalDate.now().minusDays(90);
			case "16w" -> LocalDate.now().minusDays(112);
			case "1y" -> LocalDate.now().minusDays(365);
			default -> null;
		};
		List<BestEffort> efforts = since != null
				? bestEffortRepository.findByAthleteIdAndKindAndDateGreaterThanEqualOrderByWindowAscValueDesc(athleteId, effectiveKind, since)
				: bestEffortRepository.findByAthleteIdAndKindOrderByWindowAscValueDesc(athleteId, effectiveKind);

		int topN = userService.getById(athleteId).getBestEffortTopN();
		List<BestEffort> capped = BestEffortController.capPerWindow(efforts, effectiveKind == BestEffortKind.RUNNING_PACE, topN);

		List<BestEffortResponse> data = capped.stream()
				.map(e -> new BestEffortResponse(e.getWindow(), e.getValue(), e.getUnit(), e.getDate(), e.getActivity().getId()))
				.toList();
		return new BestEffortListResponse(effectiveKind, effectivePeriod, data);
	}

	private BestEffortKind parseKind(String kind) {
		try {
			return BestEffortKind.valueOf(kind.trim().toUpperCase());
		} catch (IllegalArgumentException e) {
			throw new ValidationException(
					"kind must be one of: cycling_hr, cycling_power, running_hr, running_pace, running_power.", "kind");
		}
	}
}
