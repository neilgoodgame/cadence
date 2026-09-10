package com.cadence.api.activities;

import com.cadence.api.common.error.NotFoundException;
import com.cadence.api.workouts.StepEndType;
import com.cadence.api.workouts.Workout;
import com.cadence.api.workouts.WorkoutRepository;
import com.cadence.api.workouts.WorkoutStep;
import com.cadence.api.workouts.WorkoutStepFlattener;
import com.cadence.api.workouts.WorkoutStepFlattener.Flattened;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Derives {@link Lap} rows for an activity matched to a workout, by slicing the activity's own
 * per-second {@link Record} stream at the workout's own step boundaries (by elapsed time for
 * {@code TIME}-ended steps, by distance for {@code DISTANCE}-ended steps) - rather than trying to
 * reconcile the device's own FIT-file lap markers against the workout, which don't reliably line
 * up 1:1 with the workout's steps (a trailing "stop recording" lap, a skipped rep, an extra lap
 * press). See {@link WorkoutStepFlattener} for how repeat groups unroll while still pointing every
 * repetition at the same underlying {@link WorkoutStep} row.
 */
@Service
public class LapDerivationService {

	private final RecordRepository recordRepository;
	private final LapRepository lapRepository;
	private final WorkoutRepository workoutRepository;

	public LapDerivationService(RecordRepository recordRepository, LapRepository lapRepository,
			WorkoutRepository workoutRepository) {
		this.recordRepository = recordRepository;
		this.lapRepository = lapRepository;
		this.workoutRepository = workoutRepository;
	}

	/**
	 * Returns the derived (unsaved) laps for {@code activity}, sliced at {@code workout}'s own
	 * step boundaries, or {@code null} if derivation isn't possible for this workout/activity
	 * pair - a {@code MANUAL}-ended step anywhere in the plan (no derivable boundary: a manual
	 * step's real duration is however long the athlete held it before pressing lap, information
	 * only the device's own laps have), a {@code TIME}/{@code DISTANCE} step missing its target
	 * value, or no recorded data at all. Callers should leave the activity's existing laps
	 * untouched in that case rather than persist a garbled partial result.
	 *
	 * <p>If the activity's own recording is shorter than the plan, later steps simply produce no
	 * lap (the loop runs out of records) - a real, unremarkable case (the athlete stopped early).
	 * If it's longer, the trailing remainder becomes one final unlinked lap
	 * ({@code workoutStep = null}) rather than being dropped.
	 */
	@Transactional
	public List<Lap> deriveLaps(Activity activity, Workout workout) {
		// Re-fetch within this method's own transaction: `workout` (and `activity.getWorkout()`,
		// which is how the controller path reaches this) may come from a caller whose own
		// transaction already closed - open-in-view is off, so a detached proxy from a closed
		// session can't lazily load `steps` even inside a brand-new transaction here. A fresh,
		// same-session load is required before WorkoutStepFlattener touches the collection.
		Workout attachedWorkout =
				workoutRepository.findById(workout.getId()).orElseThrow(() -> new NotFoundException("No such workout."));
		List<Flattened> flattened = WorkoutStepFlattener.flatten(attachedWorkout);
		for (Flattened f : flattened) {
			WorkoutStep step = f.step();
			if (step.getEndType() == StepEndType.MANUAL) {
				return null;
			}
			if (step.getEndType() == StepEndType.TIME && step.getDuration() == null) {
				return null;
			}
			if (step.getEndType() == StepEndType.DISTANCE && step.getDistance() == null) {
				return null;
			}
		}

		List<Record> records = recordRepository.findByActivityIdOrderByT(activity.getId());
		if (records.isEmpty()) {
			return null;
		}

		int n = records.size();
		int recordIdx = 0;
		double offsetT = records.get(0).getT();
		double offsetDistance = records.get(0).getDistanceKm() != null ? records.get(0).getDistanceKm() : 0.0;
		List<Lap> laps = new ArrayList<>();
		int index = 1;

		for (Flattened f : flattened) {
			if (recordIdx >= n) {
				break;
			}
			WorkoutStep step = f.step();
			int endIdx = recordIdx;
			double boundary;
			boolean reached;
			if (step.getEndType() == StepEndType.TIME) {
				boundary = offsetT + step.getDuration();
				while (endIdx < n - 1 && records.get(endIdx).getT() < boundary) {
					endIdx++;
				}
				reached = records.get(endIdx).getT() >= boundary;
			}
			else {
				boundary = offsetDistance + step.getDistance() / 1000.0;
				while (endIdx < n - 1
						&& (records.get(endIdx).getDistanceKm() == null || records.get(endIdx).getDistanceKm() < boundary)) {
					endIdx++;
				}
				Double d = records.get(endIdx).getDistanceKm();
				reached = d != null && d >= boundary;
			}

			List<Record> segment = records.subList(recordIdx, endIdx + 1);
			// The governing dimension (the one endType targets) uses the *planned* value once
			// the boundary is genuinely reached, not the observed sample delta - two adjacent
			// segments share their boundary sample (it closes one segment and opens the next),
			// so diffing each segment's own first/last sample independently would undercount
			// every interior segment by one unit. The non-governing dimension has no such plan
			// to fall back on and always reflects what was actually recorded, same as an
			// un-reached (activity ended early) boundary on either dimension.
			int duration;
			double distanceKm;
			if (step.getEndType() == StepEndType.TIME) {
				duration = reached ? step.getDuration() : observedDuration(segment);
				distanceKm = observedDistanceKm(segment);
			}
			else {
				distanceKm = reached ? step.getDistance() / 1000.0 : observedDistanceKm(segment);
				duration = observedDuration(segment);
			}

			Lap lap = new Lap();
			lap.setActivity(activity);
			lap.setIndex(index);
			lap.setWorkoutStep(step);
			lap.setRepeatIndex(f.repeatIndex());
			lap.setDuration(duration);
			lap.setDistanceKm(distanceKm);
			applyAvgs(lap, segment);
			laps.add(lap);

			index++;
			recordIdx = endIdx + 1;
			offsetT = records.get(endIdx).getT();
			if (records.get(endIdx).getDistanceKm() != null) {
				offsetDistance = records.get(endIdx).getDistanceKm();
			}
		}

		if (recordIdx < n) {
			List<Record> trailing = records.subList(recordIdx, n);
			Lap lap = new Lap();
			lap.setActivity(activity);
			lap.setIndex(index);
			lap.setWorkoutStep(null);
			lap.setRepeatIndex(null);
			lap.setDuration(observedDuration(trailing));
			lap.setDistanceKm(observedDistanceKm(trailing));
			applyAvgs(lap, trailing);
			laps.add(lap);
		}

		return laps;
	}

	/**
	 * Derives and persists laps for {@code activity} from {@code workout}'s own steps,
	 * replacing whatever laps it currently has. Returns {@code false} (existing laps left
	 * untouched) if derivation wasn't possible - see {@link #deriveLaps}.
	 */
	@Transactional
	public boolean replaceLapsWithDerived(Activity activity, Workout workout) {
		List<Lap> laps = deriveLaps(activity, workout);
		if (laps == null) {
			return false;
		}
		lapRepository.deleteAll(lapRepository.findByActivityIdOrderByIndex(activity.getId()));
		lapRepository.saveAll(laps);
		return true;
	}

	private static int observedDuration(List<Record> records) {
		return records.get(records.size() - 1).getT() - records.get(0).getT();
	}

	private static double observedDistanceKm(List<Record> records) {
		Double first = null;
		Double last = null;
		int count = 0;
		for (Record r : records) {
			Double d = r.getDistanceKm();
			if (d != null) {
				if (first == null) {
					first = d;
				}
				last = d;
				count++;
			}
		}
		return count >= 2 ? last - first : 0.0;
	}

	private static void applyAvgs(Lap lap, List<Record> records) {
		int powerSum = 0;
		int powerCount = 0;
		int hrSum = 0;
		int hrCount = 0;
		for (Record r : records) {
			if (r.getPower() != null) {
				powerSum += r.getPower();
				powerCount++;
			}
			if (r.getHeartrate() != null) {
				hrSum += r.getHeartrate();
				hrCount++;
			}
		}
		lap.setAvgPower(powerCount > 0 ? Math.round((float) powerSum / powerCount) : null);
		lap.setAvgHr(hrCount > 0 ? Math.round((float) hrSum / hrCount) : null);
	}
}
