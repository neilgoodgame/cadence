package com.cadence.api.workouts;

import static org.assertj.core.api.Assertions.assertThat;

import com.cadence.api.common.domain.Sport;
import com.cadence.api.support.IntegrationTest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import com.cadence.api.workouts.WorkoutStepFlattener.Flattened;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link WorkoutStepFlattener} walks real persisted {@link WorkoutStep} rows (unlike
 * {@code WorkoutCalculations.flattenLeaves}, which operates on the ephemeral DTO tree and
 * carries no row id) - used by {@code LapDerivationService} to link a derived Lap back to the
 * WorkoutStep it came from.
 */
class WorkoutStepFlattenerTest extends IntegrationTest {

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private WorkoutRepository workoutRepository;

	private User newAthlete(String email) {
		User user = new User();
		user.setEmail(email);
		user.setName("Test Athlete");
		user.setPassword("irrelevant-for-this-test");
		return userRepository.save(user);
	}

	@Test
	void flattensAFlatStepListWithNoRepeatIndex() {
		User athlete = newAthlete("flatten-steps@example.cc");
		Workout workout = new Workout();
		workout.setCreatedBy(athlete);
		workout.setName("Flat");
		workout.setSport(Sport.BIKE);
		workout = workoutRepository.save(workout);

		WorkoutStep warmup = leaf(workout, null, 0, StepKind.WARMUP, 600, 50.0, 50.0);
		WorkoutStep cool = leaf(workout, null, 1, StepKind.COOL, 300, 40.0, 40.0);
		workout.getSteps().add(warmup);
		workout.getSteps().add(cool);
		workout = workoutRepository.save(workout);

		List<Flattened> flattened = WorkoutStepFlattener.flatten(workout);

		assertThat(flattened).extracting(f -> f.step().getKind()).containsExactly(StepKind.WARMUP, StepKind.COOL);
		assertThat(flattened).extracting(Flattened::repeatIndex).containsExactly(null, null);
	}

	@Test
	void unrollsARepeatGroupReusingTheSameRowIdPerRepetition() {
		// Mirrors "The Gorby": warmup, then 5x[work, rest].
		User athlete = newAthlete("flatten-steps-repeat@example.cc");
		Workout workout = new Workout();
		workout.setCreatedBy(athlete);
		workout.setName("The Gorby");
		workout.setSport(Sport.BIKE);
		workout = workoutRepository.save(workout);

		WorkoutStep warmup = leaf(workout, null, 0, StepKind.WARMUP, 600, 50.0, 70.0);
		WorkoutStep group = new WorkoutStep();
		group.setWorkout(workout);
		group.setOrder(1);
		group.setKind(StepKind.REPEAT);
		group.setRepeat(5);
		WorkoutStep work = leaf(workout, group, 0, StepKind.BLOCK, 300, 110.0, 110.0);
		WorkoutStep rest = leaf(workout, group, 1, StepKind.REC, 300, 50.0, 55.0);
		// All four in one cascade save (not staged across several saves): work/rest reference
		// group by object identity while it's still transient, which only resolves correctly
		// within a single, uninterrupted persistence-context flush - an intermediate save
		// would merge/detach and leave this reference stale.
		workout.getSteps().add(warmup);
		workout.getSteps().add(group);
		workout.getSteps().add(work);
		workout.getSteps().add(rest);
		workout = workoutRepository.saveAndFlush(workout);

		List<Flattened> flattened = WorkoutStepFlattener.flatten(workout);

		assertThat(flattened).hasSize(11);
		assertThat(flattened.get(0).step().getKind()).isEqualTo(StepKind.WARMUP);
		assertThat(flattened.get(0).repeatIndex()).isNull();
		for (int rep = 1; rep <= 5; rep++) {
			Flattened workLeaf = flattened.get(1 + (rep - 1) * 2);
			Flattened restLeaf = flattened.get(2 + (rep - 1) * 2);
			assertThat(workLeaf.step().getKind()).isEqualTo(StepKind.BLOCK);
			assertThat(workLeaf.repeatIndex()).isEqualTo(rep);
			assertThat(restLeaf.step().getKind()).isEqualTo(StepKind.REC);
			assertThat(restLeaf.repeatIndex()).isEqualTo(rep);
		}
		// The DB only ever stores one row per template step - every repetition of "work"
		// points at the exact same WorkoutStep row, not a duplicate.
		Set<Long> workStepIds = flattened.stream().filter(f -> f.step().getKind() == StepKind.BLOCK)
				.map(f -> f.step().getId()).collect(java.util.stream.Collectors.toSet());
		assertThat(workStepIds).hasSize(1);
	}

	private WorkoutStep leaf(Workout workout, WorkoutStep parent, int order, StepKind kind, int duration,
			double targetLow, double targetHigh) {
		WorkoutStep step = new WorkoutStep();
		step.setWorkout(workout);
		step.setParentStep(parent);
		step.setOrder(order);
		step.setKind(kind);
		step.setEndType(StepEndType.TIME);
		step.setDuration(duration);
		step.setTargetType(TargetType.POWER);
		step.setTargetLow(targetLow);
		step.setTargetHigh(targetHigh);
		return step;
	}
}
