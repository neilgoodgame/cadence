package com.cadence.api.mcp.dto;

import org.springframework.ai.mcp.annotation.McpToolParam;

/**
 * A leaf step nested inside a {@code repeat} group's {@code children} - deliberately a separate,
 * non-recursive type from {@link McpWorkoutStepInput} rather than reusing it for children too.
 * Spring AI's schema generator mishandles the self-referencing case (confirmed live: a
 * {@code List<McpWorkoutStepInput>} field generates a {@code $ref} pointing at the tool's
 * top-level input schema, not back at the step schema itself), so nesting is capped at one level
 * - a top-level step can be a {@code repeat} group of plain leaves, which covers the overwhelming
 * majority of real workouts. Repeat-of-repeat is out of scope for v1.
 */
public record McpWorkoutLeafStep(
		@McpToolParam(required = true, description = "warmup, block, rec, or cool") String kind,
		@McpToolParam(required = true, description = "time, distance, or manual") String endType,
		@McpToolParam(required = false, description = "seconds - for end_type=time") Integer duration,
		@McpToolParam(required = false, description = "meters - for end_type=distance") Integer distance,
		@McpToolParam(required = true, description = "power, hr, pace, cadence, or open") String targetType,
		@McpToolParam(required = false, description = "%-of-threshold low bound, on a 0-100 scale - "
				+ "e.g. 65 for 65% of threshold, NOT 0.65") Double targetLow,
		@McpToolParam(required = false, description = "%-of-threshold high bound, on a 0-100 scale - "
				+ "e.g. 65 for 65% of threshold, NOT 0.65") Double targetHigh,
		@McpToolParam(required = false, description = "free-text note") String note) {
}
