package com.cadence.api.workouts;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Walks a workout's actual persisted {@link WorkoutStep} rows (unlike {@link WorkoutCalculations}'s
 * {@code flattenLeaves}, which operates on the ephemeral {@link com.cadence.api.workouts.dto.WorkoutStepDto}
 * tree used only for planned duration/TSS math and carries no database row id at all) and unrolls
 * {@code repeat} groups into an ordered list of {@link Flattened} leaves. {@code repeatIndex} is the
 * 1-based repetition number when the leaf's parent is a repeat group, else {@code null}. Every
 * repetition of a repeated leaf points at the *same* {@link WorkoutStep} row - the DB only ever
 * stores one row per template step, never one per repetition - which is exactly what a derived
 * {@code Lap.workoutStep} FK is meant to capture. Used by
 * {@link com.cadence.api.activities.LapDerivationService} to slice a matched activity's real
 * records at each step's boundary.
 */
public final class WorkoutStepFlattener {

	public record Flattened(WorkoutStep step, Integer repeatIndex) {
	}

	public static List<Flattened> flatten(Workout workout) {
		Map<Long, List<WorkoutStep>> byParent = new HashMap<>();
		for (WorkoutStep step : workout.getSteps()) {
			Long parentId = step.getParentStep() != null ? step.getParentStep().getId() : null;
			byParent.computeIfAbsent(parentId, k -> new ArrayList<>()).add(step);
		}
		List<Flattened> out = new ArrayList<>();
		walk(null, byParent, out);
		return out;
	}

	private static void walk(Long parentId, Map<Long, List<WorkoutStep>> byParent, List<Flattened> out) {
		for (WorkoutStep step : byParent.getOrDefault(parentId, List.of())) {
			if (step.getKind() == StepKind.REPEAT) {
				int repeat = step.getRepeat();
				for (int rep = 1; rep <= repeat; rep++) {
					List<Flattened> children = new ArrayList<>();
					walk(step.getId(), byParent, children);
					for (Flattened child : children) {
						out.add(new Flattened(child.step(), rep));
					}
				}
			}
			else {
				out.add(new Flattened(step, null));
			}
		}
	}

	private WorkoutStepFlattener() {
	}
}
