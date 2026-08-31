package com.cadence.api.workouts;

import com.cadence.api.common.domain.Sport;
import com.cadence.api.workouts.dto.WorkoutStepDto;
import java.util.ArrayList;
import java.util.List;

/**
 * Recomputes a workout's total duration and TSS from its step tree. {@code time} steps
 * contribute their stored duration directly; a {@code distance}-ended running step targeting
 * {@code pace} or {@code power} infers one from the athlete's threshold pace (distance / (threshold
 * pace scaled by the target's %) - same %-of-threshold convention {@link com.cadence.api.athletes.ZoneService}
 * uses everywhere else) when it's known, since running power % and threshold-pace % share the
 * same %-of-60min-effort scale (running economy makes power roughly proportional to speed) -
 * unlike cycling, where a given %FTP's speed is too dependent on terrain/aero for the same
 * assumption to hold, so this only applies to {@link Sport#RUN}. Every other distance/manual
 * combination (HR/cadence/open targets, a bike workout, or no threshold pace set yet) still has
 * no such assumption available and stays at 0 - intentional, not a bug. {@code repeat} groups
 * recurse into their children and multiply by {@code repeat}, to arbitrary nesting depth.
 */
public final class WorkoutCalculations {

	public record Result(int durationSeconds, int tss) {
	}

	// Fallback power reference (watts) used to normalize a "watts"-unit step when the athlete
	// hasn't set a real ftp/criticalRunPower - matches the display-only placeholder already used
	// on the frontend (frontend/src/screens/workouts/workoutTree.ts, workoutExport.ts).
	private static final double DEFAULT_POWER_REFERENCE = 265;

	/** Returns a copy of {@code steps} where every "watts"-unit power leaf's targetLow/targetHigh
	 * are replaced by their %FTP-equivalent, so duration/TSS/chart-preview math (which only ever
	 * understands %-space) can stay completely unit-blind. The real, persisted WorkoutStep rows
	 * keep whatever the athlete actually entered - this copy is ephemeral, used only to feed
	 * computeDurationAndTss/computeChartPreview. */
	public static List<WorkoutStepDto> normalizePowerUnits(List<WorkoutStepDto> steps, Double powerReferenceWatts) {
		double reference = powerReferenceWatts != null ? powerReferenceWatts : DEFAULT_POWER_REFERENCE;
		List<WorkoutStepDto> out = new ArrayList<>(steps.size());
		for (WorkoutStepDto step : steps) {
			if (step.kind() == StepKind.REPEAT) {
				WorkoutStepDto normalizedGroup = new WorkoutStepDto(step.kind(), step.endType(), step.duration(),
						step.distance(), step.targetType(), step.targetLow(), step.targetHigh(), step.powerUnit(),
						step.target2Type(), step.target2Low(), step.target2High(), step.repeat(), step.note(),
						normalizePowerUnits(step.children(), powerReferenceWatts));
				out.add(normalizedGroup);
			} else if (step.targetType() == TargetType.POWER && step.powerUnit() == PowerUnit.WATTS) {
				Double low = step.targetLow() != null ? (step.targetLow() / reference) * 100 : null;
				Double high = step.targetHigh() != null ? (step.targetHigh() / reference) * 100 : null;
				out.add(step.withNormalizedPower(low, high));
			} else {
				out.add(step);
			}
		}
		return out;
	}

	/** {@code sport} gates the power-target distance estimate to running (see class Javadoc).
	 * {@code thresholdPaceSecPerKm} is the athlete's live PACE zone reference (seconds/km at
	 * threshold) - null if they haven't set one yet, in which case distance+pace/power steps
	 * fall back to the same 0 every other distance-ended step already gets. */
	public static Result computeDurationAndTss(List<WorkoutStepDto> steps, Sport sport, Double thresholdPaceSecPerKm) {
		return new Result(totalDuration(steps, sport, thresholdPaceSecPerKm),
				(int) Math.round(tssSum(steps, sport, thresholdPaceSecPerKm)));
	}

	private static int totalDuration(List<WorkoutStepDto> steps, Sport sport, Double thresholdPaceSecPerKm) {
		int total = 0;
		for (WorkoutStepDto step : steps) {
			if (step.kind() == StepKind.REPEAT) {
				int repeat = step.repeat() != null ? step.repeat() : 1;
				total += totalDuration(step.children(), sport, thresholdPaceSecPerKm) * repeat;
			} else {
				total += leafDuration(step, sport, thresholdPaceSecPerKm);
			}
		}
		return total;
	}

	private static int leafDuration(WorkoutStepDto step, Sport sport, Double thresholdPaceSecPerKm) {
		if (step.endType() == StepEndType.TIME) {
			return step.duration() != null ? step.duration() : 0;
		}
		if (sport == Sport.RUN && step.endType() == StepEndType.DISTANCE
				&& (step.targetType() == TargetType.PACE || step.targetType() == TargetType.POWER)
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

	private static double tssSum(List<WorkoutStepDto> steps, Sport sport, Double thresholdPaceSecPerKm) {
		double total = 0.0;
		for (WorkoutStepDto step : steps) {
			if (step.kind() == StepKind.REPEAT) {
				int repeat = step.repeat() != null ? step.repeat() : 1;
				total += tssSum(step.children(), sport, thresholdPaceSecPerKm) * repeat;
			} else {
				total += leafTss(step, sport, thresholdPaceSecPerKm);
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
	 * {@code duration} field) so an inferred distance+pace/power duration feeds TSS too.
	 */
	private static double leafTss(WorkoutStepDto step, Sport sport, Double thresholdPaceSecPerKm) {
		double avg = targetAvg(step);
		double hours = leafDuration(step, sport, thresholdPaceSecPerKm) / 3600.0;
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
	 * that leaf's actual duration, not shipping the full step tree. Uses {@link #leafDuration}
	 * (not the leaf's raw {@code duration} field) so a distance-ended running step's inferred
	 * duration sizes its bar correctly too.
	 */
	public static List<ChartPreviewPoint> computeChartPreview(List<WorkoutStepDto> steps, Sport sport,
			Double thresholdPaceSecPerKm) {
		List<ChartPreviewPoint> preview = new ArrayList<>();
		flattenLeaves(steps, sport, thresholdPaceSecPerKm, preview);
		return preview;
	}

	private static void flattenLeaves(List<WorkoutStepDto> steps, Sport sport, Double thresholdPaceSecPerKm,
			List<ChartPreviewPoint> preview) {
		for (WorkoutStepDto step : steps) {
			if (step.kind() == StepKind.REPEAT) {
				int repeat = step.repeat() != null ? step.repeat() : 1;
				for (int r = 0; r < repeat; r++) {
					flattenLeaves(step.children(), sport, thresholdPaceSecPerKm, preview);
				}
			} else {
				int duration = leafDuration(step, sport, thresholdPaceSecPerKm);
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
