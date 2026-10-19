package com.cadence.api.mcp.dto;

import org.springframework.ai.mcp.annotation.McpToolParam;

/** One step of the get_activity_custom_splits/get_race_custom_splits tools' input - mirrors
 * {@link CustomSplitStepRequest} but with a human-typed distance ("1mi"/"2km"/"400m"), same
 * convention as {@link McpWorkoutStepInput#distance()}, converted via {@code McpCustomSplits}. */
public record McpCustomSplitStepInput(
		@McpToolParam(required = true, description = "time or distance") String endType,
		@McpToolParam(required = false, description = "Seconds - for end_type=time") Integer durationS,
		@McpToolParam(required = false, description = "A number with a unit, e.g. \"400m\", \"5km\", or "
				+ "\"3.1mi\" - for end_type=distance. The unit is required, not assumed.") String distance,
		@McpToolParam(required = false, description = "e.g. \"Mile 1\" - shown as this split's label "
				+ "instead of the default.") String label) {
}
