package com.cadence.api.workouts;

import static org.assertj.core.api.Assertions.assertThat;

import com.cadence.api.common.domain.Sport;
import com.cadence.api.workouts.dto.WorkoutStepDto;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorkoutCalculationsTest {

	private static WorkoutStepDto leaf(StepKind kind, StepEndType endType, Integer duration, Integer distance,
			TargetType targetType, Double low, Double high) {
		return new WorkoutStepDto(kind, endType, duration, distance, targetType, low, high, PowerUnit.PCT_FTP,
				Target2Type.NONE, null, null, 1, "", List.of());
	}

	private static WorkoutStepDto group(int repeat, WorkoutStepDto... children) {
		return new WorkoutStepDto(StepKind.REPEAT, null, null, null, null, null, null, PowerUnit.PCT_FTP,
				Target2Type.NONE, null, null, repeat, "", List.of(children));
	}

	@Test
	void workedExampleRepeatGroup() {
		List<WorkoutStepDto> steps = List
				.of(group(4, leaf(StepKind.BLOCK, StepEndType.TIME, 300, null, TargetType.POWER, 100.0, 100.0)));

		WorkoutCalculations.Result result = WorkoutCalculations.computeDurationAndTss(steps, Sport.RUN, null);

		assertThat(result.durationSeconds()).isEqualTo(1200);
		assertThat(result.tss()).isEqualTo(33);
	}

	@Test
	void distanceAndManualStepsContributeZeroDuration() {
		List<WorkoutStepDto> steps = List.of(
				leaf(StepKind.BLOCK, StepEndType.DISTANCE, null, 5000, TargetType.POWER, 100.0, 100.0),
				leaf(StepKind.BLOCK, StepEndType.MANUAL, null, null, TargetType.OPEN, null, null));

		WorkoutCalculations.Result result = WorkoutCalculations.computeDurationAndTss(steps, Sport.RUN, null);

		assertThat(result.durationSeconds()).isEqualTo(0);
		assertThat(result.tss()).isEqualTo(0);
	}

	@Test
	void rampUsesLowHighMidpoint() {
		List<WorkoutStepDto> steps = List
				.of(leaf(StepKind.BLOCK, StepEndType.TIME, 3600, null, TargetType.POWER, 50.0, 70.0));

		WorkoutCalculations.Result result = WorkoutCalculations.computeDurationAndTss(steps, Sport.RUN, null);

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
		WorkoutCalculations.Result result = WorkoutCalculations.computeDurationAndTss(steps, Sport.RUN, 240.0);

		assertThat(result.durationSeconds()).isEqualTo(1200);
		assertThat(result.tss()).isNotEqualTo(0); // duration feeds tss too, not just duration
	}

	@Test
	void distanceStepWithPaceTargetScalesByTargetPercent() {
		// 50% target -> half the effort -> twice the time per km (slower pace).
		List<WorkoutStepDto> steps = List
				.of(leaf(StepKind.BLOCK, StepEndType.DISTANCE, null, 1000, TargetType.PACE, 50.0, 50.0));

		WorkoutCalculations.Result result = WorkoutCalculations.computeDurationAndTss(steps, Sport.RUN, 240.0);

		assertThat(result.durationSeconds()).isEqualTo(480); // 1km * (240 * 100/50) sec/km
	}

	@Test
	void distanceStepWithPaceTargetStaysZeroWithoutAKnownThresholdPace() {
		List<WorkoutStepDto> steps = List
				.of(leaf(StepKind.BLOCK, StepEndType.DISTANCE, null, 5000, TargetType.PACE, 100.0, 100.0));

		WorkoutCalculations.Result result = WorkoutCalculations.computeDurationAndTss(steps, Sport.RUN, null);

		assertThat(result.durationSeconds()).isEqualTo(0);
		assertThat(result.tss()).isEqualTo(0);
	}

	// Regression coverage for a real bug found live: a running workout's distance-ended
	// power-targeted step (e.g. "4km at 80-85% critical run power") also showed 0:00 duration
	// and 0 TSS. Running power % and threshold-pace % share the same %-of-60min-effort scale
	// (see ZoneService's PACE zone calibration), so this is now inferred exactly like a
	// pace-targeted step.
	@Test
	void distanceStepWithPowerTargetInfersDurationForARunWorkout() {
		List<WorkoutStepDto> steps = List
				.of(leaf(StepKind.BLOCK, StepEndType.DISTANCE, null, 4000, TargetType.POWER, 80.0, 85.0));

		// avg 82.5% -> pace = 275 * 100/82.5 = 333.33 sec/km -> 4km * 333.33 = 1333s.
		WorkoutCalculations.Result result = WorkoutCalculations.computeDurationAndTss(steps, Sport.RUN, 275.0);

		assertThat(result.durationSeconds()).isEqualTo(1333);
		assertThat(result.tss()).isNotEqualTo(0);
	}

	@Test
	void distanceStepWithPowerTargetStaysZeroForABikeWorkout() {
		// A cyclist's speed for a given %FTP is too dependent on terrain/aero for the running
		// pace-effort assumption to hold, so the power-target inference is run-only.
		List<WorkoutStepDto> steps = List
				.of(leaf(StepKind.BLOCK, StepEndType.DISTANCE, null, 40000, TargetType.POWER, 90.0, 90.0));

		WorkoutCalculations.Result result = WorkoutCalculations.computeDurationAndTss(steps, Sport.BIKE, 275.0);

		assertThat(result.durationSeconds()).isEqualTo(0);
		assertThat(result.tss()).isEqualTo(0);
	}

	@Test
	void distanceStepWithANonPaceOrPowerTargetStillContributesZero() {
		// HR/cadence/open targets have no distance-to-time assumption even for a run with a
		// known threshold pace - only pace/power targets do.
		List<WorkoutStepDto> steps = List
				.of(leaf(StepKind.BLOCK, StepEndType.DISTANCE, null, 5000, TargetType.HR, 100.0, 100.0));

		WorkoutCalculations.Result result = WorkoutCalculations.computeDurationAndTss(steps, Sport.RUN, 240.0);

		assertThat(result.durationSeconds()).isEqualTo(0);
	}

	@Test
	void nestedRepeatGroupsMultiplyAndSum() {
		List<WorkoutStepDto> steps = List.of(group(2,
				group(4, leaf(StepKind.BLOCK, StepEndType.TIME, 240, null, TargetType.POWER, 100.0, 100.0)),
				leaf(StepKind.REC, StepEndType.TIME, 200, null, TargetType.POWER, 50.0, 50.0)));

		WorkoutCalculations.Result result = WorkoutCalculations.computeDurationAndTss(steps, Sport.RUN, null);

		assertThat(result.durationSeconds()).isEqualTo(2 * (4 * 240 + 200));
		assertThat(result.tss()).isEqualTo(56);
	}

	private static WorkoutStepDto wattsLeaf(StepKind kind, Integer duration, Double low, Double high) {
		return new WorkoutStepDto(kind, StepEndType.TIME, duration, null, TargetType.POWER, low, high,
				PowerUnit.WATTS, Target2Type.NONE, null, null, 1, "", List.of());
	}

	@Test
	void normalizePowerUnitsConvertsWattsLeafToPctFtpEquivalent() {
		List<WorkoutStepDto> normalized = WorkoutCalculations
				.normalizePowerUnits(List.of(wattsLeaf(StepKind.BLOCK, 300, 200.0, 250.0)), 250.0);

		assertThat(normalized.get(0).targetLow()).isEqualTo(80.0);
		assertThat(normalized.get(0).targetHigh()).isEqualTo(100.0);
		assertThat(normalized.get(0).powerUnit()).isEqualTo(PowerUnit.PCT_FTP);
	}

	@Test
	void normalizePowerUnitsFallsBackToPlaceholderReferenceWhenNoneGiven() {
		List<WorkoutStepDto> normalized = WorkoutCalculations
				.normalizePowerUnits(List.of(wattsLeaf(StepKind.BLOCK, 300, 265.0, 265.0)), null);

		assertThat(normalized.get(0).targetLow()).isEqualTo(100.0);
	}

	@Test
	void normalizePowerUnitsLeavesPctFtpAndNonPowerStepsUntouched() {
		WorkoutStepDto pctFtp = leaf(StepKind.BLOCK, StepEndType.TIME, 300, null, TargetType.POWER, 80.0, 90.0);
		WorkoutStepDto hr = leaf(StepKind.BLOCK, StepEndType.TIME, 300, null, TargetType.HR, 70.0, 70.0);

		List<WorkoutStepDto> normalized = WorkoutCalculations.normalizePowerUnits(List.of(pctFtp, hr), 250.0);

		assertThat(normalized).containsExactly(pctFtp, hr);
	}

	@Test
	void normalizePowerUnitsHandlesWattsLeavesNestedInARepeatGroupAndMatchesPctFtpTss() {
		List<WorkoutStepDto> wattsSteps = List.of(group(4, wattsLeaf(StepKind.BLOCK, 300, 250.0, 250.0)));
		List<WorkoutStepDto> pctSteps = List
				.of(group(4, leaf(StepKind.BLOCK, StepEndType.TIME, 300, null, TargetType.POWER, 100.0, 100.0)));

		List<WorkoutStepDto> normalizedWatts = WorkoutCalculations.normalizePowerUnits(wattsSteps, 250.0);

		assertThat(WorkoutCalculations.computeDurationAndTss(normalizedWatts, Sport.RUN, null))
				.isEqualTo(WorkoutCalculations.computeDurationAndTss(pctSteps, Sport.RUN, null));
	}
}
