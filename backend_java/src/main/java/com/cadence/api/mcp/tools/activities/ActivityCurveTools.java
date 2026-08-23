package com.cadence.api.mcp.tools.activities;

import com.cadence.api.activities.Activity;
import com.cadence.api.activities.ActivityService;
import com.cadence.api.activities.DurationCurveMetric;
import com.cadence.api.activities.DurationCurveRepository;
import com.cadence.api.common.error.ValidationException;
import com.cadence.api.mcp.dispatch.McpScopes;
import com.cadence.api.mcp.dispatch.McpToolAuthorizer;
import com.cadence.api.mcp.dto.McpCurveSummary;
import com.cadence.api.security.AccessGuard;
import java.util.Map;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/** Calls the same {@link DurationCurveRepository} the REST {@code CurveController} does. */
@Component
public class ActivityCurveTools {

	private final ActivityService activityService;
	private final DurationCurveRepository durationCurveRepository;
	private final AccessGuard accessGuard;
	private final McpToolAuthorizer authorizer;

	public ActivityCurveTools(ActivityService activityService, DurationCurveRepository durationCurveRepository,
			AccessGuard accessGuard, McpToolAuthorizer authorizer) {
		this.activityService = activityService;
		this.durationCurveRepository = durationCurveRepository;
		this.accessGuard = accessGuard;
		this.authorizer = authorizer;
	}

	@McpTool(name = "get_activity_power_curve", description = "Get an activity's best-sustained "
			+ "power or heart rate at fixed durations (5s/1min/5min/20min/60min) - the standard "
			+ "'power curve' shape used to judge sprint/anaerobic/threshold/aerobic effort. Null "
			+ "for any duration not tracked for that metric, or if the activity has no curve data.",
			annotations = @McpTool.McpAnnotations(
					readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
	public McpCurveSummary getActivityPowerCurve(
			@McpToolParam(description = "The activity id, e.g. act_xxxxxxxxxxxx", required = true) String activityId,
			@McpToolParam(description = "Which curve: power or heartrate (default power)", required = false) String metric) {
		authorizer.requireScope(McpScopes.ACTIVITIES_READ);
		Activity activity = activityService.getActivity(activityId);
		accessGuard.requireRead(activity.getAthlete().getId());
		DurationCurveMetric effectiveMetric = parseMetric(metric);
		return durationCurveRepository.findByActivityIdAndMetric(activityId, effectiveMetric)
				.map(dc -> McpCurveSummary.from(effectiveMetric, dc.getPoints()))
				.orElse(McpCurveSummary.from(effectiveMetric, Map.of()));
	}

	/** Same reasoning as {@code ActivityReadTools.parseSport} - enum tool params take a String. */
	private DurationCurveMetric parseMetric(String metric) {
		if (metric == null || metric.isBlank()) {
			return DurationCurveMetric.POWER;
		}
		try {
			return DurationCurveMetric.valueOf(metric.trim().toUpperCase());
		} catch (IllegalArgumentException e) {
			throw new ValidationException("metric must be one of: power, heartrate.", "metric");
		}
	}
}
