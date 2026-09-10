package com.cadence.api.mcp.tools.activities;

import static org.assertj.core.api.Assertions.assertThat;

import com.cadence.api.activities.Activity;
import com.cadence.api.activities.ActivityRepository;
import com.cadence.api.activities.Lap;
import com.cadence.api.activities.LapRepository;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.security.AuthContext;
import com.cadence.api.security.AuthContextHolder;
import com.cadence.api.support.IntegrationTest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import com.cadence.api.workouts.StepEndType;
import com.cadence.api.workouts.StepKind;
import com.cadence.api.workouts.TargetType;
import com.cadence.api.workouts.Workout;
import com.cadence.api.workouts.WorkoutRepository;
import com.cadence.api.workouts.WorkoutStep;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ActivityReadToolsTest extends IntegrationTest {

	@Autowired
	private ActivityReadTools activityReadTools;

	@Autowired
	private ActivityRepository activityRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private WorkoutRepository workoutRepository;

	@Autowired
	private LapRepository lapRepository;

	@AfterEach
	void clearAuthContext() {
		AuthContextHolder.clear();
	}

	private User newUser(String email) {
		User user = new User();
		user.setEmail(email);
		user.setName("Test User");
		user.setPassword("irrelevant-for-this-test");
		return userRepository.save(user);
	}

	private void authAs(String userId, String... scopes) {
		AuthContextHolder.set(AuthContext.self(userId, Set.of(scopes), AuthContext.CredentialKind.PERSONAL_ACCESS_TOKEN));
	}

	// Regression test: getActivityLaps used to read laps via the plain (non-fetch-joined)
	// repository method, then LapMapper read workoutStep's fields - which threw
	// LazyInitializationException once this method's own implicit transaction closed
	// (open-in-view is off), for any lap actually linked to a WorkoutStep. Only surfaced once a
	// real matched-workout activity had derived laps to read back through this tool.
	@Test
	void getActivityLapsIncludesStepContextForADerivedLap() {
		User athlete = newUser("read-tool-laps-athlete@example.cc");
		Workout workout = new Workout();
		workout.setCreatedBy(athlete);
		workout.setName("W");
		workout.setSport(Sport.BIKE);
		WorkoutStep step = new WorkoutStep();
		step.setWorkout(workout);
		step.setOrder(0);
		step.setKind(StepKind.BLOCK);
		step.setEndType(StepEndType.TIME);
		step.setDuration(300);
		step.setTargetType(TargetType.POWER);
		step.setTargetLow(100.0);
		step.setTargetHigh(110.0);
		workout.getSteps().add(step);
		workout = workoutRepository.saveAndFlush(workout);

		Activity activity = new Activity();
		activity.setAthlete(athlete);
		activity.setSport(Sport.BIKE);
		activity.setName("Ride");
		activity.setStartDate(Instant.parse("2026-01-01T06:00:00Z"));
		activity.setWorkout(workout);
		activity = activityRepository.save(activity);

		Lap lap = new Lap();
		lap.setActivity(activity);
		lap.setIndex(1);
		lap.setDuration(300);
		lap.setDistanceKm(3.0);
		lap.setWorkoutStep(step);
		lapRepository.save(lap);
		authAs(athlete.getId(), "activities:read");

		var laps = activityReadTools.getActivityLaps(activity.getId());

		assertThat(laps).hasSize(1);
		assertThat(laps.get(0).stepKind()).isEqualTo(StepKind.BLOCK);
	}
}
