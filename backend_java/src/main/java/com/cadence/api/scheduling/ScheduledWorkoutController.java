package com.cadence.api.scheduling;

import com.cadence.api.scheduling.dto.ScheduleWorkoutCreateRequest;
import com.cadence.api.scheduling.dto.ScheduledWorkoutResponse;
import com.cadence.api.scheduling.dto.ScheduledWorkoutUpdateRequest;
import com.cadence.api.security.AccessGuard;
import com.cadence.api.security.AuthContextHolder;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ScheduledWorkoutController {

	private final SchedulingService schedulingService;
	private final SchedulingMapper schedulingMapper;
	private final AccessGuard accessGuard;

	public ScheduledWorkoutController(SchedulingService schedulingService, SchedulingMapper schedulingMapper, AccessGuard accessGuard) {
		this.schedulingService = schedulingService;
		this.schedulingMapper = schedulingMapper;
		this.accessGuard = accessGuard;
	}

	@PostMapping("/v1/scheduled-workouts")
	@ResponseStatus(HttpStatus.CREATED)
	public ScheduledWorkoutResponse scheduleWorkout(@Valid @RequestBody ScheduleWorkoutCreateRequest request) {
		accessGuard.requireWrite(request.athleteId());
		String assignedById = AuthContextHolder.get().sub();
		ScheduledWorkout scheduled = schedulingService.schedule(
				assignedById, request.workoutId(), request.athleteId(), request.date(), request.timeOfDay());
		return schedulingMapper.toResponse(scheduled);
	}

	@PatchMapping("/v1/scheduled-workouts/{id}")
	public ScheduledWorkoutResponse updateScheduledWorkout(@PathVariable String id, @RequestBody ScheduledWorkoutUpdateRequest request) {
		ScheduledWorkout scheduled = schedulingService.getScheduledWorkout(id);
		accessGuard.requireWrite(scheduled.getAthlete().getId());
		ScheduledWorkout updated = schedulingService.update(scheduled, request.date(), request.activityId());
		return schedulingMapper.toResponse(updated);
	}

	@DeleteMapping("/v1/scheduled-workouts/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteScheduledWorkout(@PathVariable String id) {
		ScheduledWorkout scheduled = schedulingService.getScheduledWorkout(id);
		accessGuard.requireWrite(scheduled.getAthlete().getId());
		schedulingService.delete(id);
	}
}
