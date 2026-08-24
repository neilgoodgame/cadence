package com.cadence.api.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cadence.api.common.error.ForbiddenException;
import com.cadence.api.common.error.ValidationException;
import com.cadence.api.mcp.dto.McpAthleteProfile;
import com.cadence.api.mcp.dto.McpWorkoutLeafStep;
import com.cadence.api.mcp.dto.McpWorkoutStepInput;
import com.cadence.api.mcp.tools.athletes.AthleteProfileTools;
import com.cadence.api.mcp.tools.scheduling.ScheduledWorkoutWriteTools;
import com.cadence.api.mcp.tools.workouts.WorkoutReadTools;
import com.cadence.api.mcp.tools.workouts.WorkoutWriteTools;
import com.cadence.api.scheduling.dto.ScheduledWorkoutResponse;
import com.cadence.api.security.AuthContext;
import com.cadence.api.security.AuthContextHolder;
import com.cadence.api.support.IntegrationTest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import com.cadence.api.workouts.Workout;
import com.cadence.api.workouts.WorkoutRepository;
import com.cadence.api.workouts.dto.WorkoutResponse;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Covers what the live manual verification during development already proved works end-to-end
 * through the real {@code /mcp} transport, but as a durable regression guard: tool outputs
 * persist correctly, {@link com.cadence.api.mcp.dispatch.McpToolAuthorizer} actually rejects a
 * missing scope, and cross-athlete access is still rejected through the MCP path exactly as it
 * is through REST (see {@code WorkoutLibraryIntegrationTest.folderFromAnotherAthleteCannotBeAssigned}
 * for the REST-side equivalent this mirrors).
 */
class McpToolsIntegrationTest extends IntegrationTest {

	@Autowired
	private WorkoutWriteTools workoutWriteTools;

	@Autowired
	private WorkoutReadTools workoutReadTools;

	@Autowired
	private ScheduledWorkoutWriteTools scheduledWorkoutWriteTools;

	@Autowired
	private AthleteProfileTools athleteProfileTools;

	@Autowired
	private WorkoutRepository workoutRepository;

	@Autowired
	private UserRepository userRepository;

	@AfterEach
	void clearAuthContext() {
		AuthContextHolder.clear();
	}

	private User saveAthlete(String email) {
		User athlete = new User();
		athlete.setEmail(email);
		athlete.setName("Test Athlete");
		athlete.setPassword("irrelevant-for-this-test");
		return userRepository.save(athlete);
	}

	private void authAs(String userId, String... scopes) {
		AuthContextHolder.set(AuthContext.self(userId, Set.of(scopes), AuthContext.CredentialKind.OAUTH2));
	}

	@Test
	void createWorkoutPersistsWithCorrectDurationAndUnrolledRepeats() {
		User athlete = saveAthlete("mcp-create-workout@example.cc");
		authAs(athlete.getId(), "workouts:write");

		List<McpWorkoutLeafStep> repeatChildren = List.of(
				new McpWorkoutLeafStep("block", "time", 180, null, "power", 105.0, 105.0, null),
				new McpWorkoutLeafStep("rec", "time", 120, null, "power", 55.0, 55.0, null));
		List<McpWorkoutStepInput> steps = List.of(
				new McpWorkoutStepInput("warmup", "time", 600, null, "power", 50.0, 50.0, null, null, null),
				new McpWorkoutStepInput("repeat", null, null, null, null, null, null, 3, null, repeatChildren),
				new McpWorkoutStepInput("cool", "time", 300, null, "power", 45.0, 45.0, null, null, null));

		WorkoutResponse response = workoutWriteTools.createWorkout("MCP Test", "bike", steps, null);

		assertThat(response.duration()).isEqualTo(600 + 3 * (180 + 120) + 300);
		Workout persisted = workoutRepository.findById(response.id()).orElseThrow();
		assertThat(persisted.getCreatedBy().getId()).isEqualTo(athlete.getId());
		// warmup + 3x(block+rec) + cool = 8 leaf points, matching the flattened/unrolled chart preview.
		assertThat(persisted.getChartPreview()).hasSize(8);
	}

	@Test
	void createWorkoutRejectsAnInvalidTargetType() {
		User athlete = saveAthlete("mcp-create-workout-invalid@example.cc");
		authAs(athlete.getId(), "workouts:write");

		List<McpWorkoutStepInput> steps = List.of(
				new McpWorkoutStepInput("warmup", "time", 600, null, "nonsense", 50.0, 50.0, null, null, null));

		assertThatThrownBy(() -> workoutWriteTools.createWorkout("Bad", "bike", steps, null))
				.isInstanceOf(ValidationException.class);
	}

	@Test
	void createWorkoutRejectsWithoutTheWriteScope() {
		User athlete = saveAthlete("mcp-create-workout-noscope@example.cc");
		authAs(athlete.getId()); // no scopes granted at all

		List<McpWorkoutStepInput> steps = List.of(
				new McpWorkoutStepInput("warmup", "time", 600, null, "power", 50.0, 50.0, null, null, null));

		assertThatThrownBy(() -> workoutWriteTools.createWorkout("Should Not Persist", "bike", steps, null))
				.isInstanceOf(ForbiddenException.class);
	}

	@Test
	void scheduleWorkoutPersists() {
		User athlete = saveAthlete("mcp-schedule@example.cc");
		authAs(athlete.getId(), "workouts:write", "calendar:write");

		List<McpWorkoutStepInput> steps = List.of(
				new McpWorkoutStepInput("warmup", "time", 300, null, "power", 50.0, 50.0, null, null, null));
		WorkoutResponse workout = workoutWriteTools.createWorkout("To Schedule", "bike", steps, null);

		ScheduledWorkoutResponse scheduled = scheduledWorkoutWriteTools.scheduleWorkout(
				workout.id(), LocalDate.of(2026, 9, 1), "am");

		assertThat(scheduled.workoutId()).isEqualTo(workout.id());
		assertThat(scheduled.athleteId()).isEqualTo(athlete.getId());
		assertThat(scheduled.date()).isEqualTo(LocalDate.of(2026, 9, 1));
	}

	// Regression test for a real bug found live: Claude.ai's MCP connector reported having no way
	// to move a scheduled workout, so a requested "swap two dates" left duplicate entries instead
	// of a clean move - see move_workout's Javadoc.
	@Test
	void moveWorkoutChangesTheDateOfAnExistingEntryInPlace() {
		User athlete = saveAthlete("mcp-move@example.cc");
		authAs(athlete.getId(), "workouts:write", "calendar:write");
		List<McpWorkoutStepInput> steps = List.of(
				new McpWorkoutStepInput("warmup", "time", 300, null, "power", 50.0, 50.0, null, null, null));
		WorkoutResponse workout = workoutWriteTools.createWorkout("To Move", "bike", steps, null);
		ScheduledWorkoutResponse scheduled = scheduledWorkoutWriteTools.scheduleWorkout(
				workout.id(), LocalDate.of(2026, 9, 6), "am");

		ScheduledWorkoutResponse moved = scheduledWorkoutWriteTools.moveWorkout(
				scheduled.id(), LocalDate.of(2026, 9, 13), "pm");

		assertThat(moved.id()).isEqualTo(scheduled.id());
		assertThat(moved.date()).isEqualTo(LocalDate.of(2026, 9, 13));
		assertThat(moved.timeOfDay().name()).isEqualToIgnoringCase("pm");
	}

	@Test
	void unscheduleWorkoutRemovesTheEntry() {
		User athlete = saveAthlete("mcp-unschedule@example.cc");
		authAs(athlete.getId(), "workouts:write", "calendar:write");
		List<McpWorkoutStepInput> steps = List.of(
				new McpWorkoutStepInput("warmup", "time", 300, null, "power", 50.0, 50.0, null, null, null));
		WorkoutResponse workout = workoutWriteTools.createWorkout("To Unschedule", "bike", steps, null);
		ScheduledWorkoutResponse scheduled = scheduledWorkoutWriteTools.scheduleWorkout(
				workout.id(), LocalDate.of(2026, 9, 6), "am");

		var result = scheduledWorkoutWriteTools.unscheduleWorkout(scheduled.id());

		assertThat(result.get("deleted")).isEqualTo(true);
		assertThatThrownBy(() -> scheduledWorkoutWriteTools.moveWorkout(scheduled.id(), LocalDate.of(2026, 9, 7), null))
				.isInstanceOf(com.cadence.api.common.error.NotFoundException.class);
	}

	@Test
	void getWorkoutFromAnotherAthleteIsRejected() {
		User owner = saveAthlete("mcp-workout-owner@example.cc");
		authAs(owner.getId(), "workouts:write", "activities:read");
		List<McpWorkoutStepInput> steps = List.of(
				new McpWorkoutStepInput("warmup", "time", 300, null, "power", 50.0, 50.0, null, null, null));
		WorkoutResponse workout = workoutWriteTools.createWorkout("Owner's workout", "bike", steps, null);

		User outsider = saveAthlete("mcp-workout-outsider@example.cc");
		authAs(outsider.getId(), "activities:read");

		assertThatThrownBy(() -> workoutReadTools.getWorkout(workout.id())).isInstanceOf(ForbiddenException.class);
	}

	@Test
	void getMeReturnsTheCallersOwnProfile() {
		User athlete = saveAthlete("mcp-get-me@example.cc");
		athlete.setFtp(250);
		userRepository.save(athlete);
		authAs(athlete.getId(), "activities:read");

		McpAthleteProfile profile = athleteProfileTools.getMe();

		assertThat(profile.id()).isEqualTo(athlete.getId());
		assertThat(profile.ftp()).isEqualTo(250);
	}
}
