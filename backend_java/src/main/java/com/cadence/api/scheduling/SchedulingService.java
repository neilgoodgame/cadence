package com.cadence.api.scheduling;

import com.cadence.api.activities.Activity;
import com.cadence.api.activities.ActivityRepository;
import com.cadence.api.common.error.NotFoundException;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import com.cadence.api.workouts.Workout;
import com.cadence.api.workouts.WorkoutRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SchedulingService {

	private final ScheduledWorkoutRepository scheduledWorkoutRepository;
	private final WorkoutRepository workoutRepository;
	private final UserRepository userRepository;
	private final ActivityRepository activityRepository;

	public SchedulingService(ScheduledWorkoutRepository scheduledWorkoutRepository, WorkoutRepository workoutRepository,
			UserRepository userRepository, ActivityRepository activityRepository) {
		this.scheduledWorkoutRepository = scheduledWorkoutRepository;
		this.workoutRepository = workoutRepository;
		this.userRepository = userRepository;
		this.activityRepository = activityRepository;
	}

	public List<ScheduledWorkout> getCalendar(String athleteId, LocalDate from, LocalDate to) {
		return scheduledWorkoutRepository.findByAthleteIdAndDateBetweenOrderByDate(athleteId, from, to);
	}

	/** Completed activities in range never scheduled or matched to a designed workout -
	 * GET /v1/calendar's data field only ever contains ScheduledWorkout rows. */
	public List<Activity> getUnplannedActivities(String athleteId, LocalDate from, LocalDate to) {
		Instant start = from.atStartOfDay(ZoneOffset.UTC).toInstant();
		Instant end = to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
		return activityRepository.findUnplannedInRange(athleteId, start, end);
	}

	@Transactional
	public ScheduledWorkout schedule(String assignedById, String workoutId, String athleteId, LocalDate date, TimeOfDay timeOfDay) {
		Workout workout = workoutRepository.findById(workoutId).orElseThrow(() -> new NotFoundException("No such workout."));
		User athlete = userRepository.findById(athleteId).orElseThrow(() -> new NotFoundException("No such athlete."));

		ScheduledWorkout scheduled = new ScheduledWorkout();
		scheduled.setWorkout(workout);
		scheduled.setAthlete(athlete);
		if (!assignedById.equals(athleteId)) {
			User assignedBy = userRepository.findById(assignedById).orElseThrow(() -> new NotFoundException("No such user."));
			scheduled.setAssignedBy(assignedBy);
		}
		scheduled.setDate(date);
		scheduled.setTimeOfDay(timeOfDay);
		return scheduledWorkoutRepository.save(scheduled);
	}

	public ScheduledWorkout getScheduledWorkout(String id) {
		return scheduledWorkoutRepository.findById(id).orElseThrow(() -> new NotFoundException("No such scheduled workout."));
	}

	@Transactional
	public ScheduledWorkout update(ScheduledWorkout scheduled, LocalDate date, String activityId) {
		if (date != null) {
			scheduled.setDate(date);
		}
		if (activityId != null) {
			Activity activity = activityRepository.findById(activityId)
					.orElseThrow(() -> new NotFoundException("No such activity."));
			scheduled.setActivity(activity);
			scheduled.setStatus(ScheduledWorkoutStatus.COMPLETED);
		}
		return scheduledWorkoutRepository.save(scheduled);
	}

	@Transactional
	public void delete(String id) {
		scheduledWorkoutRepository.deleteById(id);
	}
}
