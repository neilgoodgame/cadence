package com.cadence.api.workouts;

import com.cadence.api.workouts.dto.WorkoutStepDto;
import java.util.ArrayList;
import java.util.List;

/**
 * Recomputes a workout's total duration and TSS from its step tree. Only {@code time} steps
 * with a duration contribute to duration - {@code distance}/{@code manual} steps are excluded
 * because there's no pace assumption to convert distance to time without real activity history.
 * Intentional simplification, not a bug. {@code repeat} groups recurse into their children and
 * multiply by {@code repeat}, to arbitrary nesting depth.
 */
public final class WorkoutCalculations {

	public record Result(int durationSeconds, int tss) {
	}

	public static Result computeDurationAndTss(List<WorkoutStepDto> steps) {
		return new Result(totalDuration(steps), (int) Math.round(tssSum(steps)));
	}

	private static int totalDuration(List<WorkoutStepDto> steps) {
		int total = 0;
		for (WorkoutStepDto step : steps) {
			if (step.kind() == StepKind.REPEAT) {
				int repeat = step.repeat() != null ? step.repeat() : 1;
				total += totalDuration(step.children()) * repeat;
			} else {
				total += leafDuration(step);
			}
		}
		return total;
	}

	private static int leafDuration(WorkoutStepDto step) {
		if (step.endType() == StepEndType.TIME) {
			return step.duration() != null ? step.duration() : 0;
		}
		return 0;
	}

	private static double tssSum(List<WorkoutStepDto> steps) {
		double total = 0.0;
		for (WorkoutStepDto step : steps) {
			if (step.kind() == StepKind.REPEAT) {
				int repeat = step.repeat() != null ? step.repeat() : 1;
				total += tssSum(step.children()) * repeat;
			} else {
				total += leafTss(step);
			}
		}
		return total;
	}

	/**
	 * TSS ~= hours * intensity_factor^2 * 100 for power targets, using the
	 * targetLow/targetHigh midpoint (equal for a flat target, averaged for a
	 * ramp). Non-power targets have no direct IF equivalent, so pace/HR/cadence
	 * use a flatter approximation and {@code open} (no target) assumes a light
	 * effort - both intentional simplifications, matching the design prototype.
	 */
	private static double leafTss(WorkoutStepDto step) {
		double low = step.targetLow() != null ? step.targetLow() : 60;
		double high = step.targetHigh() != null ? step.targetHigh() : low;
		double avg = (low + high) / 2;
		double hours = (step.duration() != null ? step.duration() : 0) / 3600.0;
		if (step.targetType() == TargetType.POWER) {
			return hours * Math.pow(avg / 100.0, 2) * 100;
		}
		if (step.targetType() == TargetType.OPEN) {
			return hours * 55;
		}
		return hours * (avg / 100.0) * 80;
	}

	/**
	 * A small array of per-leaf average target intensity and duration (flattened/unrolled
	 * repeat groups), denormalized onto {@code Workout.chartPreview} so the library list
	 * endpoint can render a mini interval chart per card - each bar sized proportionally to
	 * that leaf's actual duration, not shipping the full step tree.
	 */
	public static List<ChartPreviewPoint> computeChartPreview(List<WorkoutStepDto> steps) {
		List<ChartPreviewPoint> preview = new ArrayList<>();
		flattenLeaves(steps, preview);
		return preview;
	}

	private static void flattenLeaves(List<WorkoutStepDto> steps, List<ChartPreviewPoint> preview) {
		for (WorkoutStepDto step : steps) {
			if (step.kind() == StepKind.REPEAT) {
				int repeat = step.repeat() != null ? step.repeat() : 1;
				for (int r = 0; r < repeat; r++) {
					flattenLeaves(step.children(), preview);
				}
			} else {
				int duration = step.duration() != null ? step.duration() : 0;
				if (step.targetType() == TargetType.OPEN) {
					preview.add(new ChartPreviewPoint(40.0, duration));
				} else {
					double low = step.targetLow() != null ? step.targetLow() : 60;
					double high = step.targetHigh() != null ? step.targetHigh() : low;
					preview.add(new ChartPreviewPoint((low + high) / 2, duration));
				}
			}
		}
	}

	private WorkoutCalculations() {
	}
}
