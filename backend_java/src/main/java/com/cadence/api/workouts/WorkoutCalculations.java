package com.cadence.api.workouts;

import com.cadence.api.workouts.dto.WorkoutStepDto;
import java.util.ArrayList;
import java.util.List;

/**
 * Recomputes a workout's total duration and TSS from its step tree. {@code time} steps
 * contribute their stored duration directly; a {@code distance}-ended step targeting
 * {@code pace} infers one from the athlete's threshold pace (distance / (threshold pace scaled
 * by the target's %) - same %-of-threshold convention {@link com.cadence.api.athletes.ZoneService}
 * uses everywhere else) when it's known, since pace and distance together already imply a time.
 * Every other distance/manual combination (power/HR/cadence/open targets, or no threshold pace
 * set yet) still has no such assumption available and stays at 0 - intentional, not a bug.
 * {@code repeat} groups recurse into their children and multiply by {@code repeat}, to arbitrary
 * nesting depth.
 */
public final class WorkoutCalculations {

	public record Result(int durationSeconds, int tss) {
	}

	/** {@code thresholdPaceSecPerKm} is the athlete's live PACE zone reference (seconds/km at
	 * threshold) - null if they haven't set one yet, in which case distance+pace steps fall back
	 * to the same 0 every other distance-ended step already gets. */
	public static Result computeDurationAndTss(List<WorkoutStepDto> steps, Double thresholdPaceSecPerKm) {
		return new Result(totalDuration(steps, thresholdPaceSecPerKm), (int) Math.round(tssSum(steps, thresholdPaceSecPerKm)));
	}

	private static int totalDuration(List<WorkoutStepDto> steps, Double thresholdPaceSecPerKm) {
		int total = 0;
		for (WorkoutStepDto step : steps) {
			if (step.kind() == StepKind.REPEAT) {
				int repeat = step.repeat() != null ? step.repeat() : 1;
				total += totalDuration(step.children(), thresholdPaceSecPerKm) * repeat;
			} else {
				total += leafDuration(step, thresholdPaceSecPerKm);
			}
		}
		return total;
	}

	private static int leafDuration(WorkoutStepDto step, Double thresholdPaceSecPerKm) {
		if (step.endType() == StepEndType.TIME) {
			return step.duration() != null ? step.duration() : 0;
		}
		if (step.endType() == StepEndType.DISTANCE && step.targetType() == TargetType.PACE
				&& step.distance() != null && thresholdPaceSecPerKm != null) {
			double avgPct = targetAvg(step);
			if (avgPct <= 0) {
				return 0;
			}
			double paceSecPerKm = thresholdPaceSecPerKm * 100.0 / avgPct;
			return (int) Math.round((step.distance() / 1000.0) * paceSecPerKm);
		}
		return 0;
	}

	private static double tssSum(List<WorkoutStepDto> steps, Double thresholdPaceSecPerKm) {
		double total = 0.0;
		for (WorkoutStepDto step : steps) {
			if (step.kind() == StepKind.REPEAT) {
				int repeat = step.repeat() != null ? step.repeat() : 1;
				total += tssSum(step.children(), thresholdPaceSecPerKm) * repeat;
			} else {
				total += leafTss(step, thresholdPaceSecPerKm);
			}
		}
		return total;
	}

	private static double targetAvg(WorkoutStepDto step) {
		double low = step.targetLow() != null ? step.targetLow() : 60;
		double high = step.targetHigh() != null ? step.targetHigh() : low;
		return (low + high) / 2;
	}

	/**
	 * TSS ~= hours * intensity_factor^2 * 100 for power targets, using the
	 * targetLow/targetHigh midpoint (equal for a flat target, averaged for a
	 * ramp). Non-power targets have no direct IF equivalent, so pace/HR/cadence
	 * use a flatter approximation and {@code open} (no target) assumes a light
	 * effort - both intentional simplifications, matching the design prototype.
	 * Reuses {@link #leafDuration} (not the step's own raw, often-null,
	 * {@code duration} field) so an inferred distance+pace duration feeds TSS too.
	 */
	private static double leafTss(WorkoutStepDto step, Double thresholdPaceSecPerKm) {
		double avg = targetAvg(step);
		double hours = leafDuration(step, thresholdPaceSecPerKm) / 3600.0;
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
