package com.cadence.api.activities;

import com.cadence.api.activities.calc.RunningPowerSanitizer;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.users.User;
import java.util.List;
import java.util.function.BiConsumer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * On-demand backfill for {@link DurationCurveComputeService} - needed because, unlike a fresh
 * upload, nothing else ever calls it for an existing activity. In particular {@code ImportReader}
 * restores raw {@code Record} rows but never recomputes duration curves from them, so every
 * activity restored from an export has none until this runs (same gap
 * {@link BestEffortRecomputeService} exists to backfill for best efforts).
 */
@Service
public class DurationCurveRecomputeService {

	private final ActivityRepository activityRepository;
	private final RecordRepository recordRepository;
	private final DurationCurveComputeService computeService;
	private final TransactionTemplate tx;

	public DurationCurveRecomputeService(ActivityRepository activityRepository, RecordRepository recordRepository,
			DurationCurveComputeService computeService, PlatformTransactionManager tm) {
		this.activityRepository = activityRepository;
		this.recordRepository = recordRepository;
		this.computeService = computeService;
		this.tx = new TransactionTemplate(tm);
	}

	/** Recompute duration curves for every eligible activity. Calls onProgress(current, total) after each. */
	public int recomputeAll(User athlete, BiConsumer<Integer, Integer> onProgress) {
		String athleteId = athlete.getId();
		List<String> ids = tx.execute(status ->
				activityRepository.findRecomputeCandidates(athleteId).stream()
						// Same reasoning as DurationCurveTasklet - a multisport parent's own mixed-sport
						// stream isn't a meaningful curve. Multisport legs are already excluded by
						// findRecomputeCandidates itself (same candidate scope as best-efforts recompute).
						.filter(a -> a.getSport() != Sport.MULTISPORT)
						.map(Activity::getId).toList());
		if (ids == null) return 0;
		for (int i = 0; i < ids.size(); i++) {
			processById(ids.get(i));
			if (onProgress != null) onProgress.accept(i + 1, ids.size());
		}
		return ids.size();
	}

	private void processById(String activityId) {
		tx.execute(status -> {
			Activity activity = activityRepository.findById(activityId).orElse(null);
			if (activity == null) return null;
			List<Record> records = recordRepository.findByActivityIdOrderByT(activityId);
			if (records.isEmpty()) return null;
			List<Integer> powerSeries = RunningPowerSanitizer.sanitize(records.stream().map(Record::getPower).toList(),
					activity.getSport(), activity.getAthlete().getMaxRunningPowerWatts());
			List<Integer> hrSeries = records.stream().map(Record::getHeartrate).toList();
			computeService.computeForActivity(activity, powerSeries, hrSeries);
			return null;
		});
	}
}
