package com.cadence.api.mcp.tools.workouts;

import com.cadence.api.common.domain.Sport;
import com.cadence.api.common.error.ValidationException;
import com.cadence.api.mcp.dispatch.McpScopes;
import com.cadence.api.mcp.dispatch.McpToolAuthorizer;
import com.cadence.api.mcp.dto.McpWorkoutLeafStep;
import com.cadence.api.mcp.dto.McpWorkoutStepInput;
import com.cadence.api.security.AccessGuard;
import com.cadence.api.users.User;
import com.cadence.api.users.UserService;
import com.cadence.api.workouts.PowerUnit;
import com.cadence.api.workouts.StepEndType;
import com.cadence.api.workouts.StepKind;
import com.cadence.api.workouts.Target2Type;
import com.cadence.api.workouts.TargetType;
import com.cadence.api.workouts.Workout;
import com.cadence.api.workouts.WorkoutMapper;
import com.cadence.api.workouts.WorkoutService;
import com.cadence.api.workouts.dto.WorkoutCreateRequest;
import com.cadence.api.workouts.dto.WorkoutResponse;
import com.cadence.api.workouts.dto.WorkoutStepDto;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * Calls the exact same {@link WorkoutService} the REST {@code WorkoutController} does. Unlike
 * REST, calling the service directly skips Spring MVC's automatic {@code @Valid} enforcement of
 * {@code WorkoutStepDto.isShapeConsistent()}, so this explicitly runs the same {@link Validator}
 * bean by hand before calling the service - same validation guarantee, not a weaker one.
 */
@Component
public class WorkoutWriteTools {

	private final WorkoutService workoutService;
	private final WorkoutMapper workoutMapper;
	private final UserService userService;
	private final AccessGuard accessGuard;
	private final McpToolAuthorizer authorizer;
	private final Validator validator;

	public WorkoutWriteTools(WorkoutService workoutService, WorkoutMapper workoutMapper, UserService userService,
			AccessGuard accessGuard, McpToolAuthorizer authorizer, Validator validator) {
		this.workoutService = workoutService;
		this.workoutMapper = workoutMapper;
		this.userService = userService;
		this.accessGuard = accessGuard;
		this.authorizer = authorizer;
		this.validator = validator;
	}

	@McpTool(name = "create_workout", description = "Create a structured interval workout in the "
			+ "athlete's workout library (not scheduled to a date - use schedule_workout for that "
			+ "afterwards). Each step is either a leaf (kind: warmup/block/rec/cool, with end_type "
			+ "time/distance/manual and a target_type power/hr/pace/cadence/open) or a repeat group "
			+ "(kind: repeat, with a repeat count and nested children - no end_type/target on the "
			+ "group itself). target_low/target_high are a %-of-threshold range on a 0-100 scale, "
			+ "e.g. 65 for 65% of threshold (NOT 0.65) - equal values for a flat target. distance "
			+ "takes a unit suffix - e.g. \"400m\", \"5km\", \"3.1mi\" - the unit is required.",
			annotations = @McpTool.McpAnnotations(
					readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false))
	public WorkoutResponse createWorkout(
			@McpToolParam(description = "Workout name", required = true) String name,
			@McpToolParam(description = "bike or run", required = true) String sport,
			@McpToolParam(description = "The step tree, top-level steps in order", required = true) List<McpWorkoutStepInput> steps,
			@McpToolParam(description = "Tags to attach, if any", required = false) List<String> tags) {
		authorizer.requireScope(McpScopes.WORKOUTS_WRITE);
		String athleteId = accessGuard.effectiveAthleteId();
		accessGuard.requireWrite(athleteId);
		User creator = userService.getById(athleteId);

		List<WorkoutStepDto> stepDtos = steps.stream().map(WorkoutWriteTools::toStepDto).toList();
		WorkoutCreateRequest request = new WorkoutCreateRequest(name, parseSport(sport), stepDtos, null, tags);
		validate(request);

		Workout workout = workoutService.createWorkout(creator, request);
		return workoutMapper.toResponse(workout);
	}

	private void validate(WorkoutCreateRequest request) {
		Set<ConstraintViolation<WorkoutCreateRequest>> violations = validator.validate(request);
		if (!violations.isEmpty()) {
			String message = violations.stream().map(ConstraintViolation::getMessage).collect(Collectors.joining("; "));
			throw new ValidationException(message);
		}
	}

	private static WorkoutStepDto toStepDto(McpWorkoutStepInput input) {
		StepKind kind = parseEnum(StepKind.class, input.kind(), "kind",
				"warmup, block, rec, cool, or repeat");
		if (kind == StepKind.REPEAT) {
			List<WorkoutStepDto> children = input.children() == null ? List.of()
					: input.children().stream().map(WorkoutWriteTools::toStepDto).toList();
			return new WorkoutStepDto(kind, null, null, null, null, null, null, PowerUnit.PCT_FTP, Target2Type.NONE,
					null, null, input.repeat() != null ? input.repeat() : 1, noteOrEmpty(input.note()), children);
		}
		StepEndType endType = parseEnum(StepEndType.class, input.endType(), "end_type", "time, distance, or manual");
		TargetType targetType = parseEnum(TargetType.class, input.targetType(), "target_type",
				"power, hr, pace, cadence, or open");
		requireTargetScale(input.targetLow(), "target_low");
		requireTargetScale(input.targetHigh(), "target_high");
		Integer distanceMeters = parseDistanceMeters(input.distance(), "distance");
		// MCP-authored power steps are always %-of-threshold - see requireTargetScale's contract.
		return new WorkoutStepDto(kind, endType, input.duration(), distanceMeters, targetType,
				input.targetLow(), input.targetHigh(), PowerUnit.PCT_FTP, Target2Type.NONE, null, null, 1,
				noteOrEmpty(input.note()), List.of());
	}

	/** {@code children} is {@link McpWorkoutLeafStep} not {@link McpWorkoutStepInput} - see that
	 * record's Javadoc. {@code repeat} is meaningless for a leaf inside a repeat group's children,
	 * so it's hardcoded to 1 rather than exposed as a field here. */
	private static WorkoutStepDto toStepDto(McpWorkoutLeafStep leaf) {
		StepKind kind = parseEnum(StepKind.class, leaf.kind(), "kind", "warmup, block, rec, or cool");
		StepEndType endType = parseEnum(StepEndType.class, leaf.endType(), "end_type", "time, distance, or manual");
		TargetType targetType = parseEnum(TargetType.class, leaf.targetType(), "target_type",
				"power, hr, pace, cadence, or open");
		requireTargetScale(leaf.targetLow(), "target_low");
		requireTargetScale(leaf.targetHigh(), "target_high");
		Integer distanceMeters = parseDistanceMeters(leaf.distance(), "distance");
		return new WorkoutStepDto(kind, endType, leaf.duration(), distanceMeters, targetType,
				leaf.targetLow(), leaf.targetHigh(), PowerUnit.PCT_FTP, Target2Type.NONE, null, null, 1,
				noteOrEmpty(leaf.note()), List.of());
	}

	/**
	 * Catches the specific mistake seen live: a caller passing a 0-1 fraction (0.65) where this
	 * tool expects a 0-100 percentage (65) - every real target is well above 1 (even an easy
	 * recovery zone is tens of percent), so any non-null value in (0, 1) is unambiguously wrong
	 * scale, not a legitimately tiny target. Silently accepting it produced a workout whose
	 * computed TSS rounded to 0 and whose power target rounded to a couple of watts, with no
	 * error anywhere to catch it.
	 */
	private static void requireTargetScale(Double value, String field) {
		if (value != null && value > 0 && value < 1) {
			throw new ValidationException(
					field + " must be a percentage on a 0-100 scale (e.g. 65 for 65%), not a fraction (e.g. 0.65).", field);
		}
	}

	private static final Pattern DISTANCE_PATTERN =
			Pattern.compile("(?i)^\\s*(\\d+(?:\\.\\d+)?)\\s*(m|km|mi|meters?|kilometers?|miles?)\\s*$");
	private static final double METERS_PER_KM = 1000.0;
	private static final double METERS_PER_MILE = 1609.344;

	/**
	 * Requires an explicit unit rather than defaulting a bare number to metres - the whole point
	 * is to close off the "which unit did the caller mean" ambiguity that {@link
	 * #requireTargetScale} has to catch after the fact for target_low/target_high; here there's
	 * no valid interpretation of a bare number to silently accept.
	 */
	private static Integer parseDistanceMeters(String distance, String field) {
		if (distance == null || distance.isBlank()) {
			return null;
		}
		Matcher matcher = DISTANCE_PATTERN.matcher(distance);
		if (!matcher.matches()) {
			throw new ValidationException(
					field + " must be a number with a unit, e.g. \"400m\", \"5km\", or \"3.1mi\".", field);
		}
		double magnitude = Double.parseDouble(matcher.group(1));
		String unit = matcher.group(2).toLowerCase();
		double meters;
		if (unit.equals("m") || unit.startsWith("meter")) {
			meters = magnitude;
		}
		else if (unit.equals("km") || unit.startsWith("kilomet")) {
			meters = magnitude * METERS_PER_KM;
		}
		else {
			meters = magnitude * METERS_PER_MILE;
		}
		return (int) Math.round(meters);
	}

	private static String noteOrEmpty(String note) {
		return note != null ? note : "";
	}

	private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, String field, String allowed) {
		if (value == null || value.isBlank()) {
			throw new ValidationException(field + " is required for a leaf step.", field);
		}
		try {
			return Enum.valueOf(type, value.trim().toUpperCase());
		} catch (IllegalArgumentException e) {
			throw new ValidationException(field + " must be one of: " + allowed + ".", field);
		}
	}

	/** Same reasoning as {@code ActivityReadTools.parseSport}. */
	private Sport parseSport(String sport) {
		try {
			return Sport.valueOf(sport.trim().toUpperCase());
		} catch (IllegalArgumentException e) {
			throw new ValidationException("sport must be bike or run.", "sport");
		}
	}
}
