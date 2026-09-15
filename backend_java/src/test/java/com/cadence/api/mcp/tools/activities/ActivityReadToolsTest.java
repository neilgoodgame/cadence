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

	@Test
	void listActivitiesQueryFiltersAndReturnsHeatStrainStats() {
		// This is the motivating case: an MCP client asking "which sessions had the highest
		// heat strain" needs both the CQL filter AND the actual value back in one call, not a
		// separate get_activity_stream_summary per activity.
		User athlete = newUser("mcp-list-heat-strain@example.cc");
		Activity severe = new Activity();
		severe.setAthlete(athlete);
		severe.setSport(Sport.BIKE);
		severe.setName("Severe");
		severe.setStartDate(Instant.parse("2026-01-01T08:00:00Z"));
		severe.setAvgHeatStrain(1.5);
		severe.setMaxHeatStrain(4.2);
		activityRepository.save(severe);

		Activity mild = new Activity();
		mild.setAthlete(athlete);
		mild.setSport(Sport.BIKE);
		mild.setName("Mild");
		mild.setStartDate(Instant.parse("2026-01-01T08:00:00Z"));
		mild.setAvgHeatStrain(0.5);
		mild.setMaxHeatStrain(1.1);
		activityRepository.save(mild);

		authAs(athlete.getId(), "activities:read");

		var result = activityReadTools.listActivities("max_heat_strain>3", null, null, null, null, null);

		assertThat(result.data()).hasSize(1);
		assertThat(result.data().get(0).name()).isEqualTo("Severe");
		assertThat(result.data().get(0).maxHeatStrain()).isEqualTo(4.2);
		assertThat(result.data().get(0).avgHeatStrain()).isEqualTo(1.5);
	}

	@Test
	void getActivityIncludesAverageAirTemperatureAndHumidity() {
		User athlete = newUser("read-tool-env-athlete@example.cc");
		Activity activity = new Activity();
		activity.setAthlete(athlete);
		activity.setSport(Sport.BIKE);
		activity.setName("Indoor trainer ride");
		activity.setStartDate(Instant.parse("2026-01-01T06:00:00Z"));
		activity.setAvgAirTemp(24.5);
		activity.setAvgHumidity(58);
		activity = activityRepository.save(activity);
		authAs(athlete.getId(), "activities:read");

		var result = activityReadTools.getActivity(activity.getId());

		assertThat(result.avgAirTemp()).isEqualTo(24.5);
		assertThat(result.avgHumidity()).isEqualTo(58);
	}
}
