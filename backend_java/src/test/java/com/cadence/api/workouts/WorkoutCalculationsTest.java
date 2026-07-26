package com.cadence.api.workouts;

import static org.assertj.core.api.Assertions.assertThat;

import com.cadence.api.workouts.dto.WorkoutStepDto;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorkoutCalculationsTest {

	private static WorkoutStepDto leaf(StepKind kind, StepEndType endType, Integer duration, Integer distance,
			TargetType targetType, Double low, Double high) {
		return new WorkoutStepDto(kind, endType, duration, distance, targetType, low, high, Target2Type.NONE, null,
				null, 1, "", List.of());
	}

	private static WorkoutStepDto group(int repeat, WorkoutStepDto... children) {
		return new WorkoutStepDto(StepKind.REPEAT, null, null, null, null, null, null, Target2Type.NONE, null, null,
				repeat, "", List.of(children));
	}

	@Test
	void workedExampleRepeatGroup() {
		List<WorkoutStepDto> steps = List
				.of(group(4, leaf(StepKind.BLOCK, StepEndType.TIME, 300, null, TargetType.POWER, 100.0, 100.0)));

		WorkoutCalculations.Result result = WorkoutCalculations.computeDurationAndTss(steps);

		assertThat(result.durationSeconds()).isEqualTo(1200);
		assertThat(result.tss()).isEqualTo(33);
	}

	@Test
	void distanceAndManualStepsContributeZeroDuration() {
		List<WorkoutStepDto> steps = List.of(
				leaf(StepKind.BLOCK, StepEndType.DISTANCE, null, 5000, TargetType.POWER, 100.0, 100.0),
				leaf(StepKind.BLOCK, StepEndType.MANUAL, null, null, TargetType.OPEN, null, null));

		WorkoutCalculations.Result result = WorkoutCalculations.computeDurationAndTss(steps);

		assertThat(result.durationSeconds()).isEqualTo(0);
		assertThat(result.tss()).isEqualTo(0);
	}

	@Test
	void rampUsesLowHighMidpoint() {
		List<WorkoutStepDto> steps = List
				.of(leaf(StepKind.BLOCK, StepEndType.TIME, 3600, null, TargetType.POWER, 50.0, 70.0));

		WorkoutCalculations.Result result = WorkoutCalculations.computeDurationAndTss(steps);

		assertThat(result.durationSeconds()).isEqualTo(3600);
		assertThat(result.tss()).isEqualTo(36); // midpoint 60% FTP -> (60/100)^2 * 100
	}

	@Test
	void nestedRepeatGroupsMultiplyAndSum() {
		List<WorkoutStepDto> steps = List.of(group(2,
				group(4, leaf(StepKind.BLOCK, StepEndType.TIME, 240, null, TargetType.POWER, 100.0, 100.0)),
				leaf(StepKind.REC, StepEndType.TIME, 200, null, TargetType.POWER, 50.0, 50.0)));

		WorkoutCalculations.Result result = WorkoutCalculations.computeDurationAndTss(steps);

		assertThat(result.durationSeconds()).isEqualTo(2 * (4 * 240 + 200));
		assertThat(result.tss()).isEqualTo(56);
	}
}
