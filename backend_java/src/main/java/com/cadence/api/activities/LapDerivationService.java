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

		List<RecordStepWalker.Step> stepSpecs = flattened.stream().map(f -> {
			WorkoutStep step = f.step();
			if (step.getEndType() == StepEndType.TIME) {
				return new RecordStepWalker.Step(RecordStepWalker.StepType.TIME, step.getDuration(), null);
			}
			return new RecordStepWalker.Step(RecordStepWalker.StepType.DISTANCE, null, (double) step.getDistance());
		}).toList();
		RecordStepWalker.Result result = RecordStepWalker.walk(records, stepSpecs);

		List<Lap> laps = new ArrayList<>();
		int index = 1;
		for (int i = 0; i < result.segments().size(); i++) {
			Flattened f = flattened.get(i);
			laps.add(lapFromSegment(activity, index++, f.step(), f.repeatIndex(), result.segments().get(i)));
		}
		if (result.trailing() != null) {
			laps.add(lapFromSegment(activity, index, null, null, result.trailing()));
		}

		return laps;
	}

	private static Lap lapFromSegment(
			Activity activity, int index, WorkoutStep step, Integer repeatIndex, RecordStepWalker.Segment segment) {
		Lap lap = new Lap();
		lap.setActivity(activity);
		lap.setIndex(index);
		lap.setWorkoutStep(step);
		lap.setRepeatIndex(repeatIndex);
		lap.setDuration(segment.timeS());
		lap.setDistanceKm(segment.distanceKm());
		applyAvgs(lap, segment.rows());
		return lap;
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
