package com.cadence.api.workouts;

import com.cadence.api.activities.Activity;
import com.cadence.api.activities.ActivityRepository;
import com.cadence.api.activities.ActivityTagRepository;
import com.cadence.api.activities.Lap;
import com.cadence.api.activities.LapRepository;
import com.cadence.api.activities.RecordRepository;
import com.cadence.api.activities.RecordRepository.ActivityAvgCoreTemp;
import com.cadence.api.common.error.NotFoundException;
import com.cadence.api.workouts.dto.WorkoutMatchComparisonResponse;
import com.cadence.api.workouts.dto.WorkoutMatchResponse;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Completed activities matched to a designed workout - computed on read from
 * {@code Activity.workout_id}, not a stored join table. {@code method} is inferred from whether
 * the activity carries the server-applied "Auto-matched" tag.
 */
@Service
public class WorkoutMatchService {

	private final WorkoutRepository workoutRepository;
	private final ActivityRepository activityRepository;
	private final ActivityTagRepository activityTagRepository;
	private final LapRepository lapRepository;
	private final RecordRepository recordRepository;

	public WorkoutMatchService(WorkoutRepository workoutRepository, ActivityRepository activityRepository,
			ActivityTagRepository activityTagRepository, LapRepository lapRepository, RecordRepository recordRepository) {
		this.workoutRepository = workoutRepository;
		this.activityRepository = activityRepository;
		this.activityTagRepository = activityTagRepository;
		this.lapRepository = lapRepository;
		this.recordRepository = recordRepository;
	}

	public List<WorkoutMatchResponse> listMatches(String workoutId, String method) {
		Workout workout = workoutRepository.findById(workoutId).orElseThrow(() -> new NotFoundException("No such workout."));
		List<Activity> activities = activityRepository.findByWorkoutId(workoutId);

		return activities.stream()
				.map(activity -> toMatch(activity, workout))
				.filter(match -> "all".equals(method) || method == null || method.equals(match.method()))
				.toList();
	}

	/** Richer per-match data for the workout comparison screen (avg HR, aerobic efficiency,
	 * environment/core-temp averages, work-block-only power) - split out from {@link
	 * #listMatches} so its lighter-weight callers (MatchedWorkoutCard, the workout detail
	 * screen's "linked activities" list) don't pay for the extra aggregate queries this needs.
	 * Both extra data sources are fetched as one batch query across every matched activity at
	 * once, not a per-activity loop, so cost doesn't scale with match count. */
	public List<WorkoutMatchComparisonResponse> listComparison(String workoutId) {
		workoutRepository.findById(workoutId).orElseThrow(() -> new NotFoundException("No such workout."));
		List<Activity> activities =
				activityRepository.findByWorkoutId(workoutId).stream().sorted(Comparator.comparing(Activity::getStartDate)).toList();
		List<String> activityIds = activities.stream().map(Activity::getId).toList();

		// Duration-weighted average of avgPower/avgHr across each activity's work-block laps -
		// not a bare mean-of-laps, since block laps can differ in length. Power and HR are
		// tracked independently (a lap missing one shouldn't skew the other's duration total).
		Map<String, double[]> workBlockPowerTotals = new HashMap<>();
		Map<String, double[]> workBlockHrTotals = new HashMap<>();
		for (Lap lap : lapRepository.findByActivityIdInAndWorkoutStepKindBlock(activityIds)) {
			int duration = lap.getDuration();
			if (duration <= 0) {
				continue;
			}
			String activityId = lap.getActivity().getId();
			Integer avgPower = lap.getAvgPower();
			if (avgPower != null) {
				double[] totals = workBlockPowerTotals.computeIfAbsent(activityId, k -> new double[2]);
				totals[0] += avgPower * duration;
				totals[1] += duration;
			}
			Integer avgHr = lap.getAvgHr();
			if (avgHr != null) {
				double[] totals = workBlockHrTotals.computeIfAbsent(activityId, k -> new double[2]);
				totals[0] += avgHr * duration;
				totals[1] += duration;
			}
		}
		Map<String, Integer> workBlockAvgPower = new HashMap<>();
		workBlockPowerTotals.forEach((activityId, totals) -> {
			if (totals[1] > 0) {
				workBlockAvgPower.put(activityId, (int) Math.round(totals[0] / totals[1]));
			}
		});
		Map<String, Integer> workBlockAvgHr = new HashMap<>();
		workBlockHrTotals.forEach((activityId, totals) -> {
			if (totals[1] > 0) {
				workBlockAvgHr.put(activityId, (int) Math.round(totals[0] / totals[1]));
			}
		});

		Map<String, Double> avgCoreTemp = new HashMap<>();
		for (ActivityAvgCoreTemp row : recordRepository.findAvgCoreTempByActivityIdIn(activityIds)) {
			if (row.getAvgCoreTemp() != null) {
				avgCoreTemp.put(row.getActivityId(), Math.round(row.getAvgCoreTemp() * 10) / 10.0);
			}
		}

		return activities.stream().map(activity -> {
			Integer avgPower = activity.getAvgPower();
			Integer avgHr = activity.getAvgHr();
			Double ef = avgPower != null && avgHr != null && avgHr != 0
					? Math.round((avgPower / (double) avgHr) * 1000) / 1000.0
					: null;
			return new WorkoutMatchComparisonResponse(activity.getId(), activity.getName(),
					activity.getStartDate().atZone(ZoneOffset.UTC).toLocalDate(), activity.getMovingTime(), avgPower, avgHr,
					ef, workBlockAvgPower.get(activity.getId()), workBlockAvgHr.get(activity.getId()),
					avgCoreTemp.get(activity.getId()), activity.getAvgAirTemp(), activity.getAvgHumidity(), activity.getTss());
		}).toList();
	}

	private WorkoutMatchResponse toMatch(Activity activity, Workout workout) {
		boolean auto = activityTagRepository.findTagNamesByActivityId(activity.getId()).contains("Auto-matched");
		String method = auto ? "auto" : "manual";
		Double confidence = auto ? closeness(activity.getMovingTime(), workout.getDuration()) : null;
		Double compliance = closeness(activity.getTss(), workout.getTss());
		return new WorkoutMatchResponse(activity.getId(), activity.getName(),
				activity.getStartDate().atZone(ZoneOffset.UTC).toLocalDate(), method, confidence, compliance,
				activity.getTss(), activity.getMovingTime(), activity.getDistanceKm(), activity.getAvgPower());
	}

	private Double closeness(double actual, double planned) {
		if (planned == 0) {
			return 0.0;
		}
		double value = 1 - Math.abs(actual - planned) / planned;
		double clamped = Math.max(0.0, Math.min(1.0, value));
		return Math.round(clamped * 100) / 100.0;
	}
}
