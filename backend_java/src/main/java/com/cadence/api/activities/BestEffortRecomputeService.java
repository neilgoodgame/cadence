package com.cadence.api.activities;

import com.cadence.api.common.domain.Sport;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import java.util.List;
import java.util.function.BiConsumer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class BestEffortRecomputeService {

	private final ActivityRepository activityRepository;
	private final RecordRepository recordRepository;
	private final BestEffortRepository bestEffortRepository;
	private final BestEffortComputeService computeService;
	private final UserRepository userRepository;
	private final TransactionTemplate tx;

	public BestEffortRecomputeService(ActivityRepository activityRepository,
			RecordRepository recordRepository, BestEffortRepository bestEffortRepository,
			BestEffortComputeService computeService, UserRepository userRepository,
			PlatformTransactionManager tm) {
		this.activityRepository = activityRepository;
		this.recordRepository = recordRepository;
		this.bestEffortRepository = bestEffortRepository;
		this.computeService = computeService;
		this.userRepository = userRepository;
		this.tx = new TransactionTemplate(tm);
	}

	/**
	 * Trim existing records to the athlete's current top-N without re-reading activity data.
	 * Use this when the limit has decreased; no-op when topN is 0 (unlimited).
	 */
	public void trimAll(User athlete) {
		int topN = athlete.getBestEffortTopN();
		if (topN == 0) return;
		tx.execute(status -> {
			for (BestEffortWindows.PowerWindow w : BestEffortWindows.HR_BEST_EFFORT_WINDOWS) {
				computeService.trim(athlete.getId(), BestEffortKind.RUNNING_HR, w.label(), false, topN);
				computeService.trim(athlete.getId(), BestEffortKind.CYCLING_HR, w.label(), false, topN);
			}
			for (BestEffortWindows.PowerWindow w : BestEffortWindows.POWER_BEST_EFFORT_WINDOWS) {
				computeService.trim(athlete.getId(), BestEffortKind.RUNNING_POWER, w.label(), false, topN);
				computeService.trim(athlete.getId(), BestEffortKind.CYCLING_POWER, w.label(), false, topN);
			}
			for (BestEffortWindows.PaceDistance d : BestEffortWindows.PACE_BEST_EFFORT_DISTANCES_KM) {
				computeService.trim(athlete.getId(), BestEffortKind.RUNNING_PACE, d.label(), true, topN);
			}
			return null;
		});
	}

	/** Recompute all kinds for the athlete. Calls onProgress(current, total) after each activity. */
	public int recomputeAll(User athlete, BiConsumer<Integer, Integer> onProgress) {
		String athleteId = athlete.getId();
		tx.execute(status -> { bestEffortRepository.deleteByAthleteId(athleteId); return null; });
		List<String> ids = tx.execute(status ->
				activityRepository.findRecomputeCandidates(athleteId).stream()
						.map(Activity::getId).toList());
		if (ids == null) return 0;
		for (int i = 0; i < ids.size(); i++) {
			processById(ids.get(i), athleteId, null);
			if (onProgress != null) onProgress.accept(i + 1, ids.size());
		}
		return ids.size();
	}

	/** Recompute a single kind for the athlete. Calls onProgress(current, total) after each activity. */
	public int recompute(User athlete, BestEffortKind kind, BiConsumer<Integer, Integer> onProgress) {
		String athleteId = athlete.getId();
		tx.execute(status -> { bestEffortRepository.deleteByAthleteIdAndKind(athleteId, kind); return null; });
		Sport requiredSport = sportForKind(kind);
		List<String> ids = tx.execute(status ->
				activityRepository.findRecomputeCandidates(athleteId).stream()
						.filter(a -> a.getSport() == requiredSport)
						.map(Activity::getId).toList());
		if (ids == null) return 0;
		for (int i = 0; i < ids.size(); i++) {
			processById(ids.get(i), athleteId, kind);
			if (onProgress != null) onProgress.accept(i + 1, ids.size());
		}
		return ids.size();
	}

	private void processById(String activityId, String athleteId, BestEffortKind kind) {
		tx.execute(status -> {
			Activity activity = activityRepository.findById(activityId).orElse(null);
			if (activity == null) return null;
			User athlete = userRepository.findById(athleteId).orElse(null);
			if (athlete == null) return null;
			List<Record> records = recordRepository.findByActivityIdOrderByT(activityId);
			if (records.isEmpty()) return null;
			List<Integer> powerSeries = records.stream().map(Record::getPower).toList();
			List<Integer> hrSeries = records.stream().map(Record::getHeartrate).toList();
			List<Double> distanceSeries = records.stream().map(Record::getDistanceKm).toList();
			if (kind == null) {
				computeService.computeForActivity(activity, athlete, powerSeries, hrSeries, distanceSeries);
			} else {
				computeService.computeForActivity(activity, athlete, kind, powerSeries, hrSeries, distanceSeries);
			}
			return null;
		});
	}

	private Sport sportForKind(BestEffortKind kind) {
		return switch (kind) {
			case CYCLING_HR, CYCLING_POWER -> Sport.BIKE;
			case RUNNING_HR, RUNNING_POWER, RUNNING_PACE -> Sport.RUN;
		};
	}
}
