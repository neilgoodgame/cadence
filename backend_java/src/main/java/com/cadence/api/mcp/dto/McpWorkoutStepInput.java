package com.cadence.api.mcp.dto;

import org.springframework.ai.mcp.annotation.McpToolParam;
import java.util.List;

/**
 * The {@code create_workout} tool's input shape for one top-level step - not a raw passthrough
 * of {@code WorkoutStepDto}, for three reasons: (1) that record's enum-typed fields hit the same
 * tool-parameter schema/binding bug documented on {@code ActivityReadTools.parseSport} (confirmed
 * live for {@code Sport}, and the underlying cause - Spring AI's schema generation reflecting raw
 * enum constant names rather than consulting {@code @JsonValue} - applies identically to
 * {@code StepKind}/{@code StepEndType}/{@code TargetType}), so every enum field here is a plain
 * lowercase String, parsed by hand in {@code WorkoutWriteTools}; (2) the secondary
 * {@code target2Type}/{@code target2Low}/{@code target2High} cadence-pairing fields are dropped
 * entirely for v1; (3) {@code children} is {@link McpWorkoutLeafStep}, not this type recursively
 * - see that record's Javadoc for why (a genuine Spring AI schema-generation bug with
 * self-referencing types, confirmed live, not a design choice).
 *
 * <p>{@code kind = "repeat"} groups carry {@code repeat} + {@code children} and leave every leaf
 * field null; every other kind is a leaf and carries {@code endType}/{@code targetType} + no
 * children - the same shape contract {@code WorkoutStepDto.isShapeConsistent()} enforces.
 */
public record McpWorkoutStepInput(
		@McpToolParam(required = true, description = "warmup, block, rec, cool, or repeat") String kind,
		@McpToolParam(required = false, description = "time, distance, or manual - leaf steps only") String endType,
		@McpToolParam(required = false, description = "seconds - for end_type=time leaf steps") Integer duration,
		@McpToolParam(required = false, description = "A number with a unit, e.g. \"400m\", \"5km\", or "
				+ "\"3.1mi\" - for end_type=distance leaf steps. The unit is required, not assumed.") String distance,
		@McpToolParam(required = false, description = "power, hr, pace, cadence, or open - leaf steps only") String targetType,
		@McpToolParam(required = false, description = "%-of-threshold low bound, on a 0-100 scale - "
				+ "e.g. 65 for 65% of threshold, NOT 0.65 - leaf steps only") Double targetLow,
		@McpToolParam(required = false, description = "%-of-threshold high bound, on a 0-100 scale - "
				+ "e.g. 65 for 65% of threshold, NOT 0.65 - leaf steps only") Double targetHigh,
		@McpToolParam(required = false, description = "repeat count - repeat groups only, default 1") Integer repeat,
		@McpToolParam(required = false, description = "free-text note") String note,
		@McpToolParam(required = false, description = "nested leaf steps - repeat groups only") List<McpWorkoutLeafStep> children) {
}
