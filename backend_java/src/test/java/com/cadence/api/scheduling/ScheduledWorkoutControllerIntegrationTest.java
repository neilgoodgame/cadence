package com.cadence.api.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cadence.api.common.domain.Sport;
import com.cadence.api.common.error.ForbiddenException;
import com.cadence.api.common.error.NotFoundException;
import com.cadence.api.security.AuthContext;
import com.cadence.api.security.AuthContextHolder;
import com.cadence.api.support.IntegrationTest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import com.cadence.api.workouts.Workout;
import com.cadence.api.workouts.WorkoutRepository;
import java.time.LocalDate;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ScheduledWorkoutControllerIntegrationTest extends IntegrationTest {

	@Autowired
	private ScheduledWorkoutController scheduledWorkoutController;

	@Autowired
	private SchedulingService schedulingService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private WorkoutRepository workoutRepository;

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

	private void authAs(String userId) {
		AuthContextHolder.set(AuthContext.self(userId, Set.of("activities:read", "activities:write"), AuthContext.CredentialKind.OAUTH2));
	}

	private Workout newWorkout(User athlete) {
		Workout workout = new Workout();
		workout.setCreatedBy(athlete);
		workout.setName("Z2 long ride");
		workout.setSport(Sport.BIKE);
		return workoutRepository.save(workout);
	}

	@Test
	void getScheduledWorkoutReturnsTheEntry() {
		User athlete = newAthlete("get-scheduled@example.cc");
		Workout workout = newWorkout(athlete);
		ScheduledWorkout scheduled = schedulingService.schedule(
				athlete.getId(), workout.getId(), athlete.getId(), LocalDate.of(2026, 9, 6), TimeOfDay.AM, "Swap if it rains");
		authAs(athlete.getId());

		var response = scheduledWorkoutController.getScheduledWorkout(scheduled.getId());

		assertThat(response.id()).isEqualTo(scheduled.getId());
		assertThat(response.workoutId()).isEqualTo(workout.getId());
		assertThat(response.date()).isEqualTo(LocalDate.of(2026, 9, 6));
		assertThat(response.notes()).isEqualTo("Swap if it rains");
	}

	@Test
	void getScheduledWorkoutIncludesTheAssignersName() {
		User athlete = newAthlete("get-scheduled-assigned-athlete@example.cc");
		User coach = newAthlete("get-scheduled-assigned-coach@example.cc");
		coach.setName("Claude.ai");
		coach.setVirtual(true);
		userRepository.save(coach);
		Workout workout = newWorkout(athlete);
		ScheduledWorkout scheduled = schedulingService.schedule(
				coach.getId(), workout.getId(), athlete.getId(), LocalDate.of(2026, 9, 6), TimeOfDay.AM, null);
		authAs(athlete.getId());

		var response = scheduledWorkoutController.getScheduledWorkout(scheduled.getId());

		assertThat(response.assignedBy()).isEqualTo(coach.getId());
		assertThat(response.assignedByName()).isEqualTo("Claude.ai");
		assertThat(response.assignedByIsVirtual()).isTrue();
	}

	@Test
	void getScheduledWorkoutSelfScheduledHasNoAssignerName() {
		User athlete = newAthlete("get-scheduled-self-athlete@example.cc");
		Workout workout = newWorkout(athlete);
		ScheduledWorkout scheduled = schedulingService.schedule(
				athlete.getId(), workout.getId(), athlete.getId(), LocalDate.of(2026, 9, 6), TimeOfDay.AM, null);
		authAs(athlete.getId());

		var response = scheduledWorkoutController.getScheduledWorkout(scheduled.getId());

		assertThat(response.assignedByName()).isNull();
		assertThat(response.assignedByIsVirtual()).isFalse();
	}

	@Test
	void getScheduledWorkoutRejectsAnOutsider() {
		User athlete = newAthlete("get-scheduled-owner@example.cc");
		User outsider = newAthlete("get-scheduled-outsider@example.cc");
		Workout workout = newWorkout(athlete);
		ScheduledWorkout scheduled = schedulingService.schedule(
				athlete.getId(), workout.getId(), athlete.getId(), LocalDate.of(2026, 9, 6), TimeOfDay.AM, null);
		authAs(outsider.getId());

		assertThatThrownBy(() -> scheduledWorkoutController.getScheduledWorkout(scheduled.getId()))
				.isInstanceOf(ForbiddenException.class);
	}

	@Test
	void getScheduledWorkoutRejectsAMissingId() {
		User athlete = newAthlete("get-scheduled-missing@example.cc");
		authAs(athlete.getId());

		assertThatThrownBy(() -> scheduledWorkoutController.getScheduledWorkout("sch_doesnotexist"))
				.isInstanceOf(NotFoundException.class);
	}
}
