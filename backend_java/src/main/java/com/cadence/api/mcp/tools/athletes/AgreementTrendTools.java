package com.cadence.api.mcp.tools.athletes;

import com.cadence.api.activities.AgreementTrendService;
import com.cadence.api.athletes.FitnessService;
import com.cadence.api.common.error.ValidationException;
import com.cadence.api.mcp.dispatch.McpScopes;
import com.cadence.api.mcp.dispatch.McpToolAuthorizer;
import com.cadence.api.security.AccessGuard;
import java.time.LocalDate;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/** Calls the exact same {@link AgreementTrendService} the REST {@code AthleteController} does. */
@Component
public class AgreementTrendTools {

	private final AgreementTrendService agreementTrendService;
	private final AccessGuard accessGuard;
	private final McpToolAuthorizer authorizer;

	public AgreementTrendTools(AgreementTrendService agreementTrendService, AccessGuard accessGuard,
			McpToolAuthorizer authorizer) {
		this.agreementTrendService = agreementTrendService;
		this.accessGuard = accessGuard;
		this.authorizer = authorizer;
	}

	@McpTool(name = "stream_agreement_trend", description = "Issue #540 (phase 5): per-activity agreement statistics between two device-class tags for one field, across the athlete's activities in a date range (default trailing 84 days) - e.g. 'how has my pedal power vs trainer power bias drifted over the last few months'. device_a/device_b are device-class tags, not source ids: power+bike is 'trainer' or 'pedals'; power+run is 'native' or 'stryd'; heartrate is 'strap' or 'wrist'; cadence is 'pedals', 'trainer', or 'watch'. sport is required when field='power' ('bike' or 'run'); optional otherwise (filters which activities are included). Each activity contributes one point when both tags resolve to a stream with at least 30 overlapping seconds; activities where a tag has no matching stream are skipped and tallied in `skipped` (no_device_a / no_device_b / insufficient_overlap) rather than erroring - 'this device wasn't used that day' is the expected common case across a date range. For field='power', exclude_dropouts (default true) excludes confirmed power-meter dropout seconds from the statistics, same as compare_streams. include_duplicates (default false) also includes same-ride-twice duplicate copies, which are otherwise excluded entirely (issue #498).",
			annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true,
					openWorldHint = false))
	public AgreementTrendService.AgreementTrendResponse streamAgreementTrend(
			@McpToolParam(description = "power, heartrate, or cadence", required = true) String field,
			@McpToolParam(description = "Device-class tag for side A - power+bike: 'trainer' or 'pedals'; "
					+ "power+run: 'native' or 'stryd'; heartrate: 'strap' or 'wrist'; cadence: 'pedals', "
					+ "'trainer', or 'watch'", required = true) String deviceA,
			@McpToolParam(description = "Device-class tag for side B - same vocabulary as device_a, must "
					+ "differ from it", required = true) String deviceB,
			@McpToolParam(description = "Required when field='power' ('bike' or 'run'); optional otherwise "
					+ "(filters which activities are included)", required = false) String sport,
			@McpToolParam(description = "Start date (YYYY-MM-DD), default 84 days before 'to'", required = false) LocalDate fromDate,
			@McpToolParam(description = "End date (YYYY-MM-DD), default today", required = false) LocalDate toDate,
			@McpToolParam(description = "Exclude confirmed power-meter dropout seconds from the statistics "
					+ "(power only, default true)", required = false) Boolean excludeDropouts,
			@McpToolParam(description = "Also include same-ride-twice duplicate copies, which are excluded by "
					+ "default (default false)", required = false) Boolean includeDuplicates) {
		authorizer.requireScope(McpScopes.ACTIVITIES_READ);
		String athleteId = accessGuard.effectiveAthleteId();
		accessGuard.requireRead(athleteId);

		LocalDate effectiveTo = toDate != null ? toDate : LocalDate.now();
		LocalDate effectiveFrom = fromDate != null ? fromDate : effectiveTo.minusDays(FitnessService.DEFAULT_FITNESS_WINDOW_DAYS);
		if (effectiveFrom.isAfter(effectiveTo)) {
			throw new ValidationException("Must not be after to_date.", "from_date");
		}

		AgreementTrendService.AgreementTrendResult result = agreementTrendService.computeAgreementTrend(athleteId,
				field, deviceA, deviceB, sport, effectiveFrom, effectiveTo, excludeDropouts == null || excludeDropouts,
				Boolean.TRUE.equals(includeDuplicates));
		return new AgreementTrendService.AgreementTrendResponse(field, deviceA, deviceB, sport, effectiveFrom,
				effectiveTo, result.points(), result.skipped());
	}
}
