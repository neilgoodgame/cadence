package com.cadence.api.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import com.cadence.api.activities.Activity;
import com.cadence.api.activities.ActivityRepository;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.support.IntegrationTest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import com.cadence.api.workouts.Workout;
import com.cadence.api.workouts.WorkoutRepository;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** GET /v1/calendar's data field only ever contains ScheduledWorkout rows - getUnplannedActivities covers completed
 * activities in range that were never scheduled or matched to a designed workout. */
class SchedulingServiceIntegrationTest extends IntegrationTest {

	@Autowired
	private SchedulingService schedulingService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private ActivityRepository activityRepository;

	@Autowired
	private WorkoutRepository workoutRepository;

	@Autowired
	private ScheduledWorkoutRepository scheduledWorkoutRepository;

	private User saveAthlete(String email) {
		User athlete = new User();
		athlete.setEmail(email);
		athlete.setName("Test Athlete");
		athlete.setPassword("irrelevant-for-this-test");
		return userRepository.save(athlete);
	}

	private Activity saveActivity(User athlete, Instant startDate) {
		Activity activity = new Activity();
		activity.setAthlete(athlete);
		activity.setSport(Sport.BIKE);
		activity.setName("Test activity");
		activity.setStartDate(startDate);
		return activityRepository.save(activity);
	}

	@Test
	void unplannedActivityInRangeIsReturned() {
		User athlete = saveAthlete("unplanned-in-range@example.cc");
		Activity activity = saveActivity(athlete, Instant.parse("2026-06-15T08:00:00Z"));

		var unplanned = schedulingService.getUnplannedActivities(athlete.getId(), LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));

		assertThat(unplanned).extracting(Activity::getId).containsExactly(activity.getId());
	}

	@Test
	void matchedActivityIsNotReturned() {
		User athlete = saveAthlete("matched@example.cc");
		Activity activity = saveActivity(athlete, Instant.parse("2026-06-15T08:00:00Z"));

		Workout workout = new Workout();
		workout.setCreatedBy(athlete);
		workout.setName("Z2 long ride");
		workout.setSport(Sport.BIKE);
		workoutRepository.save(workout);

		ScheduledWorkout scheduled = new ScheduledWorkout();
		scheduled.setWorkout(workout);
		scheduled.setAthlete(athlete);
		scheduled.setDate(LocalDate.of(2026, 6, 15));
		scheduled.setActivity(activity);
		scheduled.setStatus(ScheduledWorkoutStatus.COMPLETED);
		scheduledWorkoutRepository.save(scheduled);

		var unplanned = schedulingService.getUnplannedActivities(athlete.getId(), LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));

		assertThat(unplanned).isEmpty();
	}

	@Test
	void unplannedActivityOutsideRangeIsExcluded() {
		User athlete = saveAthlete("out-of-range@example.cc");
		saveActivity(athlete, Instant.parse("2026-07-01T08:00:00Z"));

		var unplanned = schedulingService.getUnplannedActivities(athlete.getId(), LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));

		assertThat(unplanned).isEmpty();
	}
}
