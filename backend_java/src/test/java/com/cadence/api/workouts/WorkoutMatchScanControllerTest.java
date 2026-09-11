package com.cadence.api.workouts;

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
import com.cadence.api.workouts.dto.WorkoutMatchScanCreateRequest;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

class WorkoutMatchScanControllerTest extends IntegrationTest {

	@Autowired
	private WorkoutMatchScanController controller;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private WorkoutRepository workoutRepository;

	@Autowired
	private WorkoutMatchScanRepository scanRepository;

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

	private Workout newPowerWorkout(User athlete) {
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

	@Test
	void rejectsANonPowerWorkout() {
		User athlete = newUser("scan-controller-pace@example.cc");
		Workout workout = new Workout();
		workout.setCreatedBy(athlete);
		workout.setName("Pace run");
		workout.setSport(Sport.RUN);
		WorkoutStep step = new WorkoutStep();
		step.setWorkout(workout);
		step.setOrder(0);
		step.setKind(StepKind.BLOCK);
		step.setEndType(StepEndType.TIME);
		step.setDuration(1200);
		step.setTargetType(TargetType.PACE);
		step.setTargetLow(90.0);
		step.setTargetHigh(90.0);
		workout.getSteps().add(step);
		String workoutId = workoutRepository.saveAndFlush(workout).getId();
		authAs(athlete.getId(), "workouts:write");

		assertThatThrownBy(() -> controller.createMatchScan(workoutId, null)).isInstanceOf(ValidationException.class);
	}

	@Test
	void anOutsiderWithNoShareCannotCreateAScan() {
		User athlete = newUser("scan-controller-owner@example.cc");
		User outsider = newUser("scan-controller-outsider@example.cc");
		Workout workout = newPowerWorkout(athlete);
		authAs(outsider.getId(), "workouts:write");

		assertThatThrownBy(() -> controller.createMatchScan(workout.getId(), null)).isInstanceOf(ForbiddenException.class);
	}

	@Test
	void createsAScanAndAcceptsIt() {
		User athlete = newUser("scan-controller-create@example.cc");
		Workout workout = newPowerWorkout(athlete);
		authAs(athlete.getId(), "workouts:write");

		var response = controller.createMatchScan(workout.getId(), null);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().workoutId()).isEqualTo(workout.getId());
		assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("5");
		assertThat(response.getBody().excludedStepKinds()).containsExactlyInAnyOrder("warmup", "cool");
	}

	@Test
	void acceptsAnExplicitExcludedStepKindsList() {
		User athlete = newUser("scan-controller-excluded@example.cc");
		Workout workout = newPowerWorkout(athlete);
		authAs(athlete.getId(), "workouts:write");

		var response = controller.createMatchScan(workout.getId(), new WorkoutMatchScanCreateRequest(List.of("warmup")));

		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().excludedStepKinds()).containsExactly("warmup");
	}

	@Test
	void rejectsAnInvalidStepKind() {
		User athlete = newUser("scan-controller-invalid-kind@example.cc");
		Workout workout = newPowerWorkout(athlete);
		authAs(athlete.getId(), "workouts:write");

		assertThatThrownBy(() -> controller.createMatchScan(workout.getId(), new WorkoutMatchScanCreateRequest(List.of("nonsense"))))
				.isInstanceOf(ValidationException.class);
	}

	@Test
	void aSecondCreateWhileAScanIsActiveReturnsTheExistingOne() {
		User athlete = newUser("scan-controller-dedup@example.cc");
		Workout workout = newPowerWorkout(athlete);
		WorkoutMatchScan existing = new WorkoutMatchScan();
		existing.setWorkout(workout);
		existing.setStatus(WorkoutMatchScanStatus.PROCESSING);
		existing = scanRepository.save(existing);
		authAs(athlete.getId(), "workouts:write");

		var response = controller.createMatchScan(workout.getId(), null);

		// The dedup path returns the SAME existing scan id rather than creating a new one - the
		// real assertion here; a workout-wide findAll().hasSize(1) would be a false negative in
		// a shared, non-transactional test DB where other test methods create their own scans.
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().id()).isEqualTo(existing.getId());
	}

	@Test
	void anOutsiderWithNoShareCannotPollAScan() {
		User athlete = newUser("scan-controller-poll-owner@example.cc");
		User outsider = newUser("scan-controller-poll-outsider@example.cc");
		Workout workout = newPowerWorkout(athlete);
		WorkoutMatchScan scan = new WorkoutMatchScan();
		scan.setWorkout(workout);
		scan = scanRepository.save(scan);
		authAs(outsider.getId(), "workouts:read");
		String scanId = scan.getId();

		assertThatThrownBy(() -> controller.getMatchScan(workout.getId(), scanId)).isInstanceOf(ForbiddenException.class);
	}

	@Test
	void pollingAQueuedScanIncludesRetryAfterAndNoCandidatesYet() {
		User athlete = newUser("scan-controller-poll-queued@example.cc");
		Workout workout = newPowerWorkout(athlete);
		WorkoutMatchScan scan = new WorkoutMatchScan();
		scan.setWorkout(workout);
		scan = scanRepository.save(scan);
		authAs(athlete.getId(), "workouts:read");

		var response = controller.getMatchScan(workout.getId(), scan.getId());

		assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("5");
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().status()).isEqualTo(WorkoutMatchScanStatus.QUEUED);
		assertThat(response.getBody().candidates()).isEmpty();
	}
}
