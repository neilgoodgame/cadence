package com.cadence.api.activities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cadence.api.activities.dto.ActivityWorkoutMatchCandidateResponse;
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
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ActivityWorkoutMatchCandidatesControllerTest extends IntegrationTest {

	@Autowired
	private ActivityController controller;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private WorkoutRepository workoutRepository;

	@Autowired
	private ActivityRepository activityRepository;

	@Autowired
	private RecordRepository recordRepository;

	@AfterEach
	void clearAuthContext() {
		AuthContextHolder.clear();
	}

	private User newAthlete(String email) {
		User user = new User();
		user.setEmail(email);
		user.setName("Athlete");
		user.setPassword("irrelevant-for-this-test");
		return userRepository.save(user);
	}

	private void authAs(String userId, String... scopes) {
		AuthContextHolder.set(AuthContext.self(userId, Set.of(scopes), AuthContext.CredentialKind.PERSONAL_ACCESS_TOKEN));
	}

	private Workout newTwoPhaseWorkout(User athlete, int phaseSeconds) {
		Workout workout = new Workout();
		workout.setCreatedBy(athlete);
		workout.setName("Matching workout");
		workout.setSport(Sport.BIKE);
		workout.setDuration(phaseSeconds * 2);
		workout = workoutRepository.save(workout);

		WorkoutStep low = new WorkoutStep();
		low.setWorkout(workout);
		low.setOrder(0);
		low.setKind(StepKind.BLOCK);
		low.setEndType(StepEndType.TIME);
		low.setDuration(phaseSeconds);
		low.setTargetType(TargetType.POWER);
		low.setTargetLow(60.0);
		low.setTargetHigh(60.0);
		workout.getSteps().add(low);

		WorkoutStep high = new WorkoutStep();
		high.setWorkout(workout);
		high.setOrder(1);
		high.setKind(StepKind.BLOCK);
		high.setEndType(StepEndType.TIME);
		high.setDuration(phaseSeconds);
		high.setTargetType(TargetType.POWER);
		high.setTargetLow(110.0);
		high.setTargetHigh(110.0);
		workout.getSteps().add(high);

		return workoutRepository.save(workout);
	}

	private Activity newActivity(User athlete, Instant start, Sport sport, int movingTime) {
		Activity activity = new Activity();
		activity.setAthlete(athlete);
		activity.setSport(sport);
		activity.setName("Ride");
		activity.setStartDate(start);
		activity.setMovingTime(movingTime);
		return activityRepository.save(activity);
	}

	private void seedTwoPhaseRecords(Activity activity, Instant start, int phaseSeconds) {
		for (int t = 0; t <= phaseSeconds * 2; t++) {
			Record record = new Record();
			record.setId(new RecordId(activity.getId(), start.plusSeconds(t)));
			record.setActivity(activity);
			record.setT(t);
			record.setPower(t < phaseSeconds ? 150 : 280);
			recordRepository.save(record);
		}
	}

	@Test
	void ranksTheAthletesWorkoutLibraryByCorrelation() {
		User athlete = newAthlete("wmc-controller-athlete@example.cc");
		Workout matching = newTwoPhaseWorkout(athlete, 1800);
		Instant start = Instant.parse("2026-07-01T06:00:00Z");
		Activity activity = newActivity(athlete, start, Sport.BIKE, 3600);
		seedTwoPhaseRecords(activity, start, 1800);
		authAs(athlete.getId(), "activities:read");

		List<ActivityWorkoutMatchCandidateResponse> response = controller.getWorkoutMatchCandidates(activity.getId(), null);

		assertThat(response).hasSize(1);
		assertThat(response.get(0).workoutId()).isEqualTo(matching.getId());
		assertThat(response.get(0).correlation()).isGreaterThan(0.99);
	}

	@Test
	void wideningToleranceIncludesACandidateOutsideTheDefaultWindow() {
		// Regression coverage for a real case found live: a distance-based activity's actual
		// moving time can legitimately fall well outside a fixed-duration workout's planned
		// duration, well past the default 60s tolerance - toleranceSeconds lets a caller widen
		// the pre-filter per-request to investigate that without changing the default.
		User athlete = newAthlete("wmc-controller-tolerance@example.cc");
		Workout matching = newTwoPhaseWorkout(athlete, 1700);
		Instant start = Instant.parse("2026-07-03T06:00:00Z");
		Activity activity = newActivity(athlete, start, Sport.BIKE, 3600);
		seedTwoPhaseRecords(activity, start, 1700);
		authAs(athlete.getId(), "activities:read");

		assertThat(controller.getWorkoutMatchCandidates(activity.getId(), null)).isEmpty();

		List<ActivityWorkoutMatchCandidateResponse> widened = controller.getWorkoutMatchCandidates(activity.getId(), 250);

		assertThat(widened).hasSize(1);
		assertThat(widened.get(0).workoutId()).isEqualTo(matching.getId());
	}

	@Test
	void rejectsANegativeTolerance() {
		User athlete = newAthlete("wmc-controller-negative@example.cc");
		Activity activity = newActivity(athlete, Instant.parse("2026-07-04T06:00:00Z"), Sport.BIKE, 3600);
		authAs(athlete.getId(), "activities:read");

		assertThatThrownBy(() -> controller.getWorkoutMatchCandidates(activity.getId(), -1)).isInstanceOf(ValidationException.class);
	}

	@Test
	void anOutsiderWithNoShareCannotSeeCandidates() {
		User athlete = newAthlete("wmc-controller-owner@example.cc");
		User outsider = newAthlete("wmc-controller-outsider@example.cc");
		Activity activity = newActivity(athlete, Instant.parse("2026-07-02T06:00:00Z"), Sport.BIKE, 3600);
		authAs(outsider.getId(), "activities:read");

		assertThatThrownBy(() -> controller.getWorkoutMatchCandidates(activity.getId(), null)).isInstanceOf(ForbiddenException.class);
	}
}
