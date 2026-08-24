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

		WorkoutCalculations.Result result = WorkoutCalculations.computeDurationAndTss(steps, null);

		assertThat(result.durationSeconds()).isEqualTo(1200);
		assertThat(result.tss()).isEqualTo(33);
	}

	@Test
	void distanceAndManualStepsContributeZeroDuration() {
		List<WorkoutStepDto> steps = List.of(
				leaf(StepKind.BLOCK, StepEndType.DISTANCE, null, 5000, TargetType.POWER, 100.0, 100.0),
				leaf(StepKind.BLOCK, StepEndType.MANUAL, null, null, TargetType.OPEN, null, null));

		WorkoutCalculations.Result result = WorkoutCalculations.computeDurationAndTss(steps, null);

		assertThat(result.durationSeconds()).isEqualTo(0);
		assertThat(result.tss()).isEqualTo(0);
	}

	@Test
	void rampUsesLowHighMidpoint() {
		List<WorkoutStepDto> steps = List
				.of(leaf(StepKind.BLOCK, StepEndType.TIME, 3600, null, TargetType.POWER, 50.0, 70.0));

		WorkoutCalculations.Result result = WorkoutCalculations.computeDurationAndTss(steps, null);

		assertThat(result.durationSeconds()).isEqualTo(3600);
		assertThat(result.tss()).isEqualTo(36); // midpoint 60% FTP -> (60/100)^2 * 100
	}

	// Regression coverage for a real gap found live: a distance-ended pace-targeted step
	// (typical for a running workout - "run 5km at threshold pace") showed 0:00 duration and
	// 0 TSS, since there was previously no assumption available to convert distance to time.
	// Now inferred from the athlete's threshold pace when it's set.
	@Test
	void distanceStepWithPaceTargetInfersDurationFromThresholdPace() {
		List<WorkoutStepDto> steps = List
				.of(leaf(StepKind.BLOCK, StepEndType.DISTANCE, null, 5000, TargetType.PACE, 100.0, 100.0));

		// 240 sec/km (4:00/km) at 100% target -> 5km * 240 sec/km = 1200s.
		WorkoutCalculations.Result result = WorkoutCalculations.computeDurationAndTss(steps, 240.0);

		assertThat(result.durationSeconds()).isEqualTo(1200);
		assertThat(result.tss()).isNotEqualTo(0); // duration feeds tss too, not just duration
	}

	@Test
	void distanceStepWithPaceTargetScalesByTargetPercent() {
		// 50% target -> half the effort -> twice the time per km (slower pace).
		List<WorkoutStepDto> steps = List
				.of(leaf(StepKind.BLOCK, StepEndType.DISTANCE, null, 1000, TargetType.PACE, 50.0, 50.0));

		WorkoutCalculations.Result result = WorkoutCalculations.computeDurationAndTss(steps, 240.0);

		assertThat(result.durationSeconds()).isEqualTo(480); // 1km * (240 * 100/50) sec/km
	}

	@Test
	void distanceStepWithPaceTargetStaysZeroWithoutAKnownThresholdPace() {
		List<WorkoutStepDto> steps = List
				.of(leaf(StepKind.BLOCK, StepEndType.DISTANCE, null, 5000, TargetType.PACE, 100.0, 100.0));

		WorkoutCalculations.Result result = WorkoutCalculations.computeDurationAndTss(steps, null);

		assertThat(result.durationSeconds()).isEqualTo(0);
		assertThat(result.tss()).isEqualTo(0);
	}

	@Test
	void distanceStepWithANonPaceTargetStillContributesZero() {
		// Power/HR/cadence targets have no distance-to-time assumption even with a known
		// threshold pace - only pace targets do.
		List<WorkoutStepDto> steps = List
				.of(leaf(StepKind.BLOCK, StepEndType.DISTANCE, null, 5000, TargetType.POWER, 100.0, 100.0));

		WorkoutCalculations.Result result = WorkoutCalculations.computeDurationAndTss(steps, 240.0);

		assertThat(result.durationSeconds()).isEqualTo(0);
	}

	@Test
	void nestedRepeatGroupsMultiplyAndSum() {
		List<WorkoutStepDto> steps = List.of(group(2,
				group(4, leaf(StepKind.BLOCK, StepEndType.TIME, 240, null, TargetType.POWER, 100.0, 100.0)),
				leaf(StepKind.REC, StepEndType.TIME, 200, null, TargetType.POWER, 50.0, 50.0)));

		WorkoutCalculations.Result result = WorkoutCalculations.computeDurationAndTss(steps, null);

		assertThat(result.durationSeconds()).isEqualTo(2 * (4 * 240 + 200));
		assertThat(result.tss()).isEqualTo(56);
	}
}
