package com.cadence.api.mcp.dto;

import com.cadence.api.activities.DurationCurveMetric;
import java.util.Map;

/**
 * Fixed key durations picked out of {@code DurationCurveResponse}'s full points map (which can
 * carry 5-8 duration keys already, but this pins the contract to the durations that matter for a
 * conversational "how good was my sprint/5-min/FTP/hour effort" question, keyed by seconds -
 * matching how the underlying map is actually keyed, e.g. {@code "300"} not {@code "5min"}).
 * {@code null} means that duration isn't tracked for this metric (e.g. no 5s point for
 * heart rate) or the activity has no curve data at all.
 */
public record McpCurveSummary(DurationCurveMetric metric, Double sec5, Double min1, Double min5, Double min20, Double min60) {

	public static McpCurveSummary from(DurationCurveMetric metric, Map<String, Double> points) {
		return new McpCurveSummary(metric,
				points.get("5"), points.get("60"), points.get("300"), points.get("1200"), points.get("3600"));
	}
}
