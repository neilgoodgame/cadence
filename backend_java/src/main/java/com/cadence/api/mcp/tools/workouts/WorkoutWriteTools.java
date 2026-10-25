package com.cadence.api.mcp.tools.workouts;

import com.cadence.api.activities.Tag;
import com.cadence.api.activities.TagMapper;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.common.error.ValidationException;
import com.cadence.api.mcp.McpUnits;
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
import com.cadence.api.workouts.dto.WorkoutTagAttachResponse;
import com.cadence.api.workouts.dto.WorkoutUpdateRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.List;
import java.util.Set;
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
	private final TagMapper tagMapper;
	private final UserService userService;
	private final AccessGuard accessGuard;
	private final McpToolAuthorizer authorizer;
	private final Validator validator;

	public WorkoutWriteTools(WorkoutService workoutService, WorkoutMapper workoutMapper, TagMapper tagMapper,
			UserService userService, AccessGuard accessGuard, McpToolAuthorizer authorizer, Validator validator) {
		this.workoutService = workoutService;
		this.workoutMapper = workoutMapper;
		this.tagMapper = tagMapper;
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
		return workoutService.toResponse(workout);
	}

	@McpTool(name = "update_workout", description = "Rename or re-author an existing saved workout. "
			+ "Only the fields you pass are changed - omit a field to leave it as-is. Passing steps "
			+ "replaces the entire step tree (same shape as create_workout's steps argument); duration "
			+ "and TSS are recomputed from it. Pass folderId=\"\" to clear the folder (omit folderId "
			+ "entirely to leave it untouched). Tags are managed separately - see tag_workout/"
			+ "untag_workout. The workout's sport cannot be changed after creation - delete "
			+ "and recreate it instead. Edits do not affect already-completed activities matched to "
			+ "this workout.",
			annotations = @McpTool.McpAnnotations(
					readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false))
	public WorkoutResponse updateWorkout(
			@McpToolParam(description = "The workout's id", required = true) String workoutId,
			@McpToolParam(description = "New name, if changing it", required = false) String name,
			@McpToolParam(description = "The full new step tree, if replacing it", required = false) List<McpWorkoutStepInput> steps,
			@McpToolParam(description = "New folder id, \"\" to clear, omit to leave untouched", required = false) String folderId) {
		authorizer.requireScope(McpScopes.WORKOUTS_WRITE);
		Workout workout = workoutService.getWorkout(workoutId);
		accessGuard.requireWrite(workout.getCreatedBy().getId());

		List<WorkoutStepDto> stepDtos = steps == null ? null : steps.stream().map(WorkoutWriteTools::toStepDto).toList();
		WorkoutUpdateRequest request = new WorkoutUpdateRequest(name, stepDtos, folderId);
		validate(request);

		Workout updated = workoutService.updateWorkout(workoutId, request);
		return workoutService.toResponse(updated);
	}

	@McpTool(name = "delete_workout", description = "Permanently delete a workout from the library. "
			+ "Any scheduled calendar instances of this workout are deleted too; completed activities "
			+ "previously matched to it keep their history but lose the match link. This cannot be undone.",
			annotations = @McpTool.McpAnnotations(
					readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = false))
	public void deleteWorkout(@McpToolParam(description = "The workout's id", required = true) String workoutId) {
		authorizer.requireScope(McpScopes.WORKOUTS_WRITE);
		Workout workout = workoutService.getWorkout(workoutId);
		accessGuard.requireWrite(workout.getCreatedBy().getId());
		workoutService.deleteWorkout(workoutId);
	}

	@McpTool(name = "tag_workout", description = "Attach a tag to a workout (from list_workouts/"
			+ "get_workout) by name - creates the tag if the athlete doesn't already have one with "
			+ "this exact name. Workouts and activities share the same tag catalog (see list_tags), "
			+ "so a tag created here can also be attached to an activity, and vice versa. A no-op if "
			+ "the workout already carries this tag.",
			annotations = @McpTool.McpAnnotations(
					readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false))
	public WorkoutTagAttachResponse tagWorkout(
			@McpToolParam(description = "The workout's id", required = true) String workoutId,
			@McpToolParam(description = "The tag name, e.g. \"Key session\"", required = true) String name) {
		authorizer.requireScope(McpScopes.WORKOUTS_WRITE);
		if (name == null || name.isBlank()) {
			throw new ValidationException("name must not be blank.", "name");
		}
		Workout workout = workoutService.getWorkout(workoutId);
		accessGuard.requireWrite(workout.getCreatedBy().getId());
		Tag tag = workoutService.attachTag(workout, workout.getCreatedBy(), null, name);
		return new WorkoutTagAttachResponse(workoutId, tagMapper.toResponse(tag));
	}

	@McpTool(name = "untag_workout", description = "Remove a tag from a workout (from "
			+ "list_workouts/get_workout) - the tag itself stays in the catalog, still available "
			+ "for other workouts/activities (see delete_tag to remove it entirely instead).",
			annotations = @McpTool.McpAnnotations(
					readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false))
	public WorkoutResponse untagWorkout(
			@McpToolParam(description = "The workout's id", required = true) String workoutId,
			@McpToolParam(description = "The tag's id (see get_workout's tags)", required = true) String tagId) {
		authorizer.requireScope(McpScopes.WORKOUTS_WRITE);
		Workout workout = workoutService.getWorkout(workoutId);
		accessGuard.requireWrite(workout.getCreatedBy().getId());
		workoutService.detachTag(workoutId, tagId);
		return workoutService.toResponse(workout);
	}

	private <T> void validate(T request) {
		Set<ConstraintViolation<T>> violations = validator.validate(request);
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
		Integer distanceMeters = McpUnits.parseDistanceMeters(input.distance(), "distance");
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
		Integer distanceMeters = McpUnits.parseDistanceMeters(leaf.distance(), "distance");
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
