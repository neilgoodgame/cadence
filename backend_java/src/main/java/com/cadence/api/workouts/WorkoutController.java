package com.cadence.api.workouts;

import com.cadence.api.activities.Tag;
import com.cadence.api.activities.TagMapper;
import com.cadence.api.activities.dto.TagAttachRequest;
import com.cadence.api.common.paging.DataListResponse;
import com.cadence.api.security.AccessGuard;
import com.cadence.api.users.User;
import com.cadence.api.users.UserService;
import com.cadence.api.workouts.dto.WorkoutCreateRequest;
import com.cadence.api.workouts.dto.WorkoutDetailResponse;
import com.cadence.api.workouts.dto.WorkoutMatchComparisonResponse;
import com.cadence.api.workouts.dto.WorkoutMatchResponse;
import com.cadence.api.workouts.dto.WorkoutResponse;
import com.cadence.api.workouts.dto.WorkoutTagAttachResponse;
import com.cadence.api.workouts.dto.WorkoutUpdateRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WorkoutController {

	private final WorkoutService workoutService;
	private final WorkoutMapper workoutMapper;
	private final WorkoutMatchService workoutMatchService;
	private final UserService userService;
	private final AccessGuard accessGuard;
	private final TagMapper tagMapper;

	public WorkoutController(WorkoutService workoutService, WorkoutMapper workoutMapper,
			WorkoutMatchService workoutMatchService, UserService userService, AccessGuard accessGuard,
			TagMapper tagMapper) {
		this.workoutService = workoutService;
		this.workoutMapper = workoutMapper;
		this.workoutMatchService = workoutMatchService;
		this.userService = userService;
		this.accessGuard = accessGuard;
		this.tagMapper = tagMapper;
	}

	@GetMapping("/v1/workouts")
	public DataListResponse<WorkoutResponse> listWorkouts(
			// @RequestParam binds by the literal query-string key, unaffected by Jackson's
			// snake_case body naming strategy - the frontend sends `folder_id`, so this needs
			// an explicit `value` (the other params are single words, so no mismatch there).
			@RequestParam(value = "folder_id", required = false) String folderId,
			@RequestParam(required = false) String tag,
			@RequestParam(required = false) String sport,
			@RequestParam(required = false) String search,
			@RequestParam(required = false) String sort) {
		String athleteId = accessGuard.effectiveAthleteId();
		accessGuard.requireRead(athleteId);
		var workouts = workoutService.listWorkouts(athleteId, folderId, tag, sport, search, sort);
		return new DataListResponse<>(workouts.stream().map(workoutService::toResponse).toList());
	}

	@PostMapping("/v1/workouts")
	@ResponseStatus(HttpStatus.CREATED)
	public WorkoutResponse createWorkout(@Valid @RequestBody WorkoutCreateRequest request) {
		String athleteId = accessGuard.effectiveAthleteId();
		accessGuard.requireWrite(athleteId);
		User creator = userService.getById(athleteId);
		Workout workout = workoutService.createWorkout(creator, request);
		return workoutService.toResponse(workout);
	}

	@GetMapping("/v1/workouts/{id}")
	public WorkoutDetailResponse getWorkout(@PathVariable String id) {
		Workout workout = workoutService.getWorkoutWithSteps(id);
		accessGuard.requireRead(workout.getCreatedBy().getId());
		var steps = workoutMapper.toStepTree(workout.getSteps());
		return new WorkoutDetailResponse(workoutService.toResponse(workout), steps);
	}

	@PatchMapping("/v1/workouts/{id}")
	public WorkoutResponse updateWorkout(@PathVariable String id, @Valid @RequestBody WorkoutUpdateRequest request) {
		Workout workout = workoutService.getWorkout(id);
		accessGuard.requireWrite(workout.getCreatedBy().getId());
		Workout updated = workoutService.updateWorkout(id, request);
		return workoutService.toResponse(updated);
	}

	@DeleteMapping("/v1/workouts/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteWorkout(@PathVariable String id) {
		Workout workout = workoutService.getWorkout(id);
		accessGuard.requireWrite(workout.getCreatedBy().getId());
		workoutService.deleteWorkout(id);
	}

	@GetMapping("/v1/workouts/{id}/matches")
	public DataListResponse<WorkoutMatchResponse> listWorkoutMatches(@PathVariable String id,
			@RequestParam(defaultValue = "all") String method) {
		Workout workout = workoutService.getWorkout(id);
		accessGuard.requireRead(workout.getCreatedBy().getId());
		return new DataListResponse<>(workoutMatchService.listMatches(id, method));
	}

	@GetMapping("/v1/workouts/{id}/matches/compare")
	public DataListResponse<WorkoutMatchComparisonResponse> compareWorkoutMatches(@PathVariable String id) {
		Workout workout = workoutService.getWorkout(id);
		accessGuard.requireRead(workout.getCreatedBy().getId());
		return new DataListResponse<>(workoutMatchService.listComparison(id));
	}

	/** Mirrors TagController.tagActivity exactly - same shared Tag catalog. */
	@PostMapping("/v1/workouts/{id}/tags")
	@ResponseStatus(HttpStatus.CREATED)
	public WorkoutTagAttachResponse tagWorkout(@PathVariable String id, @Valid @RequestBody TagAttachRequest request) {
		Workout workout = workoutService.getWorkout(id);
		accessGuard.requireWrite(workout.getCreatedBy().getId());
		Tag tag = workoutService.attachTag(workout, workout.getCreatedBy(), request.tagId(), request.name());
		return new WorkoutTagAttachResponse(id, tagMapper.toResponse(tag));
	}

	/** Mirrors TagController.untagActivity exactly. */
	@DeleteMapping("/v1/workouts/{id}/tags/{tagId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void untagWorkout(@PathVariable String id, @PathVariable String tagId) {
		Workout workout = workoutService.getWorkout(id);
		accessGuard.requireWrite(workout.getCreatedBy().getId());
		workoutService.detachTag(id, tagId);
	}
}
