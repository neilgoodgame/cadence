package com.cadence.api.workouts;

import com.cadence.api.workouts.dto.WorkoutStepDto;
import java.util.List;

/**
 * Recomputes a workout's total duration and TSS from its step list. Only {@code time} steps
 * with a duration contribute - {@code distance}/{@code manual} steps are excluded because there's
 * no pace assumption to convert distance to time without real activity history. Intentional
 * simplification, not a bug.
 */
public final class WorkoutCalculations {

	public record Result(int durationSeconds, int tss) {
	}

	public static Result computeDurationAndTss(List<WorkoutStepDto> steps) {
		int durationSeconds = 0;
		double tss = 0.0;
		for (WorkoutStepDto step : steps) {
			int repeat = step.repeat() != null ? step.repeat() : 1;
			if (step.endType() == StepEndType.TIME && step.duration() != null) {
				int stepDuration = step.duration() * repeat;
				durationSeconds += stepDuration;
				double targetPct = step.targetPct() != null ? step.targetPct() : 0;
				tss += (stepDuration / 3600.0) * Math.pow(targetPct / 100.0, 2) * 100;
			}
		}
		return new Result(durationSeconds, (int) Math.round(tss));
	}

	private WorkoutCalculations() {
	}
}
