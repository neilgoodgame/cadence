package com.cadence.api.workouts;

import com.cadence.api.common.paging.DataListResponse;
import com.cadence.api.security.AccessGuard;
import com.cadence.api.users.User;
import com.cadence.api.users.UserService;
import com.cadence.api.workouts.dto.WorkoutCreateRequest;
import com.cadence.api.workouts.dto.WorkoutDetailResponse;
import com.cadence.api.workouts.dto.WorkoutMatchResponse;
import com.cadence.api.workouts.dto.WorkoutResponse;
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

	public WorkoutController(WorkoutService workoutService, WorkoutMapper workoutMapper,
			WorkoutMatchService workoutMatchService, UserService userService, AccessGuard accessGuard) {
		this.workoutService = workoutService;
		this.workoutMapper = workoutMapper;
		this.workoutMatchService = workoutMatchService;
		this.userService = userService;
		this.accessGuard = accessGuard;
	}

	@GetMapping("/v1/workouts")
	public DataListResponse<WorkoutResponse> listWorkouts() {
		String athleteId = accessGuard.effectiveAthleteId();
		accessGuard.requireRead(athleteId);
		return new DataListResponse<>(workoutService.listWorkouts(athleteId).stream().map(workoutMapper::toResponse).toList());
	}

	@PostMapping("/v1/workouts")
	@ResponseStatus(HttpStatus.CREATED)
	public WorkoutResponse createWorkout(@Valid @RequestBody WorkoutCreateRequest request) {
		String athleteId = accessGuard.effectiveAthleteId();
		accessGuard.requireWrite(athleteId);
		User creator = userService.getById(athleteId);
		Workout workout = workoutService.createWorkout(creator, request);
		return workoutMapper.toResponse(workout);
	}

	@GetMapping("/v1/workouts/{id}")
	public WorkoutDetailResponse getWorkout(@PathVariable String id) {
		Workout workout = workoutService.getWorkoutWithSteps(id);
		accessGuard.requireRead(workout.getCreatedBy().getId());
		var steps = workout.getSteps().stream().map(workoutMapper::toDto).toList();
		return new WorkoutDetailResponse(workoutMapper.toResponse(workout), steps);
	}

	@PatchMapping("/v1/workouts/{id}")
	public WorkoutResponse updateWorkout(@PathVariable String id, @Valid @RequestBody WorkoutUpdateRequest request) {
		Workout workout = workoutService.getWorkout(id);
		accessGuard.requireWrite(workout.getCreatedBy().getId());
		Workout updated = workoutService.updateWorkout(id, request);
		return workoutMapper.toResponse(updated);
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
}
