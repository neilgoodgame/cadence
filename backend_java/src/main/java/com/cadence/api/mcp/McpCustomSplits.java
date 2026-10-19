package com.cadence.api.mcp;

import com.cadence.api.activities.dto.CustomSplitStepRequest;
import com.cadence.api.common.error.ValidationException;
import com.cadence.api.mcp.dto.McpCustomSplitStepInput;
import java.util.List;

/** Converts the get_activity_custom_splits/get_race_custom_splits tools' own step shape
 * (duration_s seconds; distance a unit string like "1mi"/"2km"/"400m") into
 * CustomSplitStepRequest's (distanceKm a plain double) - mirrors the Python backend's
 * activities/mcp.py's convert_custom_steps_input. */
public final class McpCustomSplits {

	private McpCustomSplits() {}

	public static List<CustomSplitStepRequest> convert(List<McpCustomSplitStepInput> steps) {
		if (steps == null) {
			throw new ValidationException("steps must be a non-empty list.", "steps");
		}
		return steps.stream().map(s -> {
			Integer meters = s.distance() != null ? McpUnits.parseDistanceMeters(s.distance(), "steps.distance") : null;
			Double distanceKm = meters != null ? meters / 1000.0 : null;
			return new CustomSplitStepRequest(s.endType(), s.durationS(), distanceKm, s.label());
		}).toList();
	}
}
