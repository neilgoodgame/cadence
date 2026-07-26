package com.cadence.api.workouts;

import static org.assertj.core.api.Assertions.assertThat;

import com.cadence.api.common.domain.Sport;
import com.cadence.api.support.IntegrationTest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import com.cadence.api.workouts.dto.WorkoutCreateRequest;
import com.cadence.api.workouts.dto.WorkoutStepDto;
import com.cadence.api.workouts.dto.WorkoutUpdateRequest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class WorkoutServiceIntegrationTest extends IntegrationTest {

	@Autowired
	private WorkoutService workoutService;

	@Autowired
	private WorkoutMapper workoutMapper;

	@Autowired
	private UserRepository userRepository;

	private static WorkoutStepDto leaf(StepKind kind, StepEndType endType, Integer duration, TargetType targetType,
			Double low, Double high) {
		return new WorkoutStepDto(kind, endType, duration, null, targetType, low, high, Target2Type.NONE, null, null,
				1, "", List.of());
	}

	private static WorkoutStepDto group(int repeat, WorkoutStepDto... children) {
		return new WorkoutStepDto(StepKind.REPEAT, null, null, null, null, null, null, Target2Type.NONE, null, null,
				repeat, "", List.of(children));
	}

	private User saveAthlete(String email) {
		User athlete = new User();
		athlete.setEmail(email);
		athlete.setName("Test Athlete");
		athlete.setPassword("irrelevant-for-this-test");
		return userRepository.save(athlete);
	}

	@Test
	void createRoundTripsNestedRepeatGroups() {
		User athlete = saveAthlete("nested-create@example.cc");
		List<WorkoutStepDto> steps = List.of(
				leaf(StepKind.WARMUP, StepEndType.TIME, 600, TargetType.POWER, 50.0, 50.0),
				group(4, leaf(StepKind.BLOCK, StepEndType.TIME, 300, TargetType.POWER, 100.0, 100.0)),
				leaf(StepKind.COOL, StepEndType.TIME, 300, TargetType.POWER, 40.0, 40.0));

		Workout workout = workoutService.createWorkout(athlete,
				new WorkoutCreateRequest("VO2 Max 5x5", Sport.BIKE, steps));

		assertThat(workout.getDuration()).isEqualTo(1200 + 600 + 300);
		assertThat(workout.getTss()).isGreaterThan(0);

		Workout fetched = workoutService.getWorkoutWithSteps(workout.getId());
		List<WorkoutStepDto> tree = workoutMapper.toStepTree(fetched.getSteps());

		assertThat(tree).extracting(WorkoutStepDto::kind)
				.containsExactly(StepKind.WARMUP, StepKind.REPEAT, StepKind.COOL);
		WorkoutStepDto repeatGroup = tree.get(1);
		assertThat(repeatGroup.repeat()).isEqualTo(4);
		assertThat(repeatGroup.children()).extracting(WorkoutStepDto::kind).containsExactly(StepKind.BLOCK);
	}

	@Test
	void nestedRepeatGroupsCanContainNestedGroups() {
		User athlete = saveAthlete("double-nested@example.cc");
		List<WorkoutStepDto> steps = List.of(group(2,
				group(4, leaf(StepKind.BLOCK, StepEndType.TIME, 240, TargetType.POWER, 100.0, 100.0)),
				leaf(StepKind.REC, StepEndType.TIME, 200, TargetType.POWER, 50.0, 50.0)));

		Workout workout = workoutService.createWorkout(athlete,
				new WorkoutCreateRequest("Double nested", Sport.BIKE, steps));

		assertThat(workout.getDuration()).isEqualTo(2 * (4 * 240 + 200));

		Workout fetched = workoutService.getWorkoutWithSteps(workout.getId());
		List<WorkoutStepDto> tree = workoutMapper.toStepTree(fetched.getSteps());

		assertThat(tree).hasSize(1);
		WorkoutStepDto outer = tree.get(0);
		assertThat(outer.kind()).isEqualTo(StepKind.REPEAT);
		assertThat(outer.children()).extracting(WorkoutStepDto::kind).containsExactly(StepKind.REPEAT, StepKind.REC);
		WorkoutStepDto inner = outer.children().get(0);
		assertThat(inner.repeat()).isEqualTo(4);
		assertThat(inner.children()).extracting(WorkoutStepDto::kind).containsExactly(StepKind.BLOCK);
	}

	@Test
	void updateReplacesStepsAndRecomputesTotals() {
		User athlete = saveAthlete("replace-steps@example.cc");
		Workout workout = workoutService.createWorkout(athlete, new WorkoutCreateRequest("Original", Sport.BIKE,
				List.of(leaf(StepKind.BLOCK, StepEndType.TIME, 60, TargetType.POWER, 50.0, 50.0))));

		List<WorkoutStepDto> newSteps = List
				.of(group(4, leaf(StepKind.BLOCK, StepEndType.TIME, 300, TargetType.POWER, 100.0, 100.0)));
		Workout updated = workoutService.updateWorkout(workout.getId(), new WorkoutUpdateRequest(null, newSteps));

		assertThat(updated.getDuration()).isEqualTo(1200);
		assertThat(updated.getTss()).isEqualTo(33);

		Workout fetched = workoutService.getWorkoutWithSteps(workout.getId());
		assertThat(fetched.getSteps()).hasSize(2); // repeat group + its 1 child
	}
}
