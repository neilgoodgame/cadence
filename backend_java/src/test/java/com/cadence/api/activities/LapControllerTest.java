package com.cadence.api.activities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cadence.api.common.domain.Sport;
import com.cadence.api.common.error.ForbiddenException;
import com.cadence.api.common.error.ValidationException;
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

class LapControllerTest extends IntegrationTest {

	@Autowired
	private LapController lapController;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private WorkoutRepository workoutRepository;

	@Autowired
	private ActivityRepository activityRepository;

	@Autowired
	private RecordRepository recordRepository;

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

	private Workout newSingleStepWorkout(User athlete) {
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
		step.setTargetHigh(100.0);
		workout.getSteps().add(step);
		return workoutRepository.saveAndFlush(workout);
	}

	private Activity newActivity(User athlete, Instant start, Workout workout) {
		Activity activity = new Activity();
		activity.setAthlete(athlete);
		activity.setSport(Sport.BIKE);
		activity.setName("Ride");
		activity.setStartDate(start);
		activity.setWorkout(workout);
		return activityRepository.save(activity);
	}

	private void seedRecords(Activity activity, Instant start, int totalSeconds) {
		for (int t = 0; t < totalSeconds; t++) {
			Record record = new Record();
			record.setId(new RecordId(activity.getId(), start.plusSeconds(t)));
			record.setActivity(activity);
			record.setT(t);
			record.setPower(200);
			recordRepository.save(record);
		}
	}

	@Test
	void regeneratesLapsFromTheMatchedWorkout() {
		User athlete = newUser("regen-laps@example.cc");
		Workout workout = newSingleStepWorkout(athlete);
		Instant start = Instant.parse("2026-01-01T06:00:00Z");
		Activity activity = newActivity(athlete, start, workout);
		seedRecords(activity, start, 301);
		Lap existing = new Lap();
		existing.setActivity(activity);
		existing.setIndex(1);
		existing.setDuration(300);
		existing.setDistanceKm(3.0);
		lapRepository.save(existing);
		authAs(athlete.getId(), "activities:write");

		var response = lapController.regenerateLaps(activity.getId());

		assertThat(response.data()).hasSize(1);
		// stepDuration is the step's own planned duration (300, matching newSingleStepWorkout) -
		// distinct from the lap's own `duration`, which is what was actually recorded. Used by the
		// frontend to tell two identically-targeted-but-differently-timed steps apart (see
		// lapPresentation.ts::summarizeSteps).
		var lap = response.data().get(0);
		assertThat(lap.stepDuration()).isEqualTo(300);
		assertThat(lap.stepDistance()).isNull();
	}

	@Test
	void rejectsAnActivityWithNoMatchedWorkout() {
		User athlete = newUser("regen-laps-no-workout@example.cc");
		Activity activity = newActivity(athlete, Instant.parse("2026-01-02T06:00:00Z"), null);
		authAs(athlete.getId(), "activities:write");

		assertThatThrownBy(() -> lapController.regenerateLaps(activity.getId())).isInstanceOf(ValidationException.class);
	}

	@Test
	void rejectsWhenDerivationIsNotPossible() {
		User athlete = newUser("regen-laps-impossible@example.cc");
		Workout workout = newSingleStepWorkout(athlete);
		Activity activity = newActivity(athlete, Instant.parse("2026-01-03T06:00:00Z"), workout); // no records
		authAs(athlete.getId(), "activities:write");

		assertThatThrownBy(() -> lapController.regenerateLaps(activity.getId())).isInstanceOf(ValidationException.class);
	}

	@Test
	void anOutsiderWithNoShareCannotRegenerate() {
		User athlete = newUser("regen-laps-outsider-athlete@example.cc");
		User outsider = newUser("regen-laps-outsider@example.cc");
		Workout workout = newSingleStepWorkout(athlete);
		Activity activity = newActivity(athlete, Instant.parse("2026-01-04T06:00:00Z"), workout);
		authAs(outsider.getId(), "activities:write");

		assertThatThrownBy(() -> lapController.regenerateLaps(activity.getId())).isInstanceOf(ForbiddenException.class);
	}
}
