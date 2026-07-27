package com.cadence.api.activities;

import com.cadence.api.common.domain.Sport;
import com.cadence.api.common.error.ValidationException;
import com.cadence.api.security.AccessGuard;
import com.cadence.api.users.UserService;
import com.cadence.api.workouts.WorkoutInferenceService;
import com.cadence.api.workouts.dto.InferredWorkoutResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InferWorkoutController {

	private final ActivityService activityService;
	private final LapRepository lapRepository;
	private final WorkoutInferenceService workoutInferenceService;
	private final UserService userService;
	private final AccessGuard accessGuard;

	public InferWorkoutController(ActivityService activityService, LapRepository lapRepository,
			WorkoutInferenceService workoutInferenceService, UserService userService, AccessGuard accessGuard) {
		this.activityService = activityService;
		this.lapRepository = lapRepository;
		this.workoutInferenceService = workoutInferenceService;
		this.userService = userService;
		this.accessGuard = accessGuard;
	}

	@GetMapping("/v1/activities/{id}/infer-workout")
	public InferredWorkoutResponse inferWorkout(@PathVariable String id,
			@RequestParam(value = "auto_detect_repeats", defaultValue = "true") boolean autoDetectRepeats) {
		Activity activity = activityService.getActivity(id);
		String athleteId = activity.getAthlete().getId();
		accessGuard.requireRead(athleteId);
		if (activity.getSport() != Sport.BIKE && activity.getSport() != Sport.RUN) {
			throw new ValidationException("Only bike and run activities can be inferred into a workout.", "sport");
		}
		var laps = lapRepository.findByActivityIdOrderByIndex(id);
		if (laps.isEmpty()) {
			throw new ValidationException("This activity has no laps to infer a workout from.", "laps");
		}
		// activity.getAthlete() is a lazy proxy that can't be initialized once the request's
		// Hibernate session closes; WorkoutInferenceService reads FTP/LTHR off the athlete, so
		// swap in the fully-loaded entity before handing the activity off.
		activity.setAthlete(userService.getById(athleteId));
		return workoutInferenceService.infer(activity, laps, autoDetectRepeats);
	}
}
