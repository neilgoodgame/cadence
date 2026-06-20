package com.cadence.api.workouts;

import com.cadence.api.activities.Activity;
import com.cadence.api.activities.ActivityRepository;
import com.cadence.api.activities.ActivityTagRepository;
import com.cadence.api.common.error.NotFoundException;
import com.cadence.api.workouts.dto.WorkoutMatchResponse;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Completed activities matched to a designed workout - computed on read from
 * {@code Activity.workout_id}, not a stored join table. {@code method} is inferred from whether
 * the activity carries the server-applied "Auto-matched" tag.
 */
@Service
public class WorkoutMatchService {

	private final WorkoutRepository workoutRepository;
	private final ActivityRepository activityRepository;
	private final ActivityTagRepository activityTagRepository;

	public WorkoutMatchService(WorkoutRepository workoutRepository, ActivityRepository activityRepository,
			ActivityTagRepository activityTagRepository) {
		this.workoutRepository = workoutRepository;
		this.activityRepository = activityRepository;
		this.activityTagRepository = activityTagRepository;
	}

	public List<WorkoutMatchResponse> listMatches(String workoutId, String method) {
		Workout workout = workoutRepository.findById(workoutId).orElseThrow(() -> new NotFoundException("No such workout."));
		List<Activity> activities = activityRepository.findByWorkoutId(workoutId);

		return activities.stream()
				.map(activity -> toMatch(activity, workout))
				.filter(match -> "all".equals(method) || method == null || method.equals(match.method()))
				.toList();
	}

	private WorkoutMatchResponse toMatch(Activity activity, Workout workout) {
		boolean auto = activityTagRepository.findTagNamesByActivityId(activity.getId()).contains("Auto-matched");
		String method = auto ? "auto" : "manual";
		Double confidence = auto ? closeness(activity.getMovingTime(), workout.getDuration()) : null;
		Double compliance = closeness(activity.getTss(), workout.getTss());
		return new WorkoutMatchResponse(activity.getId(), activity.getName(),
				activity.getStartDate().atZone(ZoneOffset.UTC).toLocalDate(), method, confidence, compliance);
	}

	private Double closeness(double actual, double planned) {
		if (planned == 0) {
			return 0.0;
		}
		double value = 1 - Math.abs(actual - planned) / planned;
		double clamped = Math.max(0.0, Math.min(1.0, value));
		return Math.round(clamped * 100) / 100.0;
	}
}
