package com.cadence.api.activities;

import com.cadence.api.common.domain.Sport;
import com.cadence.api.users.User;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.BiConsumer;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Bulk counterpart to {@link ActivityThresholdSuggestionService#recomputeThresholds} - re-runs
 * {@link ThresholdDetectionService#detect} across many of the athlete's activities at once,
 * optionally narrowed by sport and/or date range, so activities that predate the feature (or
 * were imported, which also skips detection) can be checked in bulk rather than one at a time.
 */
@Service
public class ThresholdRecomputeService {

	public record Summary(int checked, int flagged) {
	}

	private final ActivityRepository activityRepository;
	private final RecordRepository recordRepository;
	private final ThresholdDetectionService thresholdDetectionService;
	private final TransactionTemplate tx;

	public ThresholdRecomputeService(ActivityRepository activityRepository, RecordRepository recordRepository,
			ThresholdDetectionService thresholdDetectionService, PlatformTransactionManager tm) {
		this.activityRepository = activityRepository;
		this.recordRepository = recordRepository;
		this.thresholdDetectionService = thresholdDetectionService;
		this.tx = new TransactionTemplate(tm);
	}

	/** Calls onProgress(current, total) after each activity. */
	public Summary recomputeAll(User athlete, Sport sportFilter, LocalDate after, LocalDate before,
			BiConsumer<Integer, Integer> onProgress) {
		List<String> ids = tx.execute(status ->
				activityRepository.findAll(candidateSpec(athlete.getId(), sportFilter, after, before)).stream()
						.map(Activity::getId).toList());
		if (ids == null || ids.isEmpty()) {
			return new Summary(0, 0);
		}

		int flagged = 0;
		for (int i = 0; i < ids.size(); i++) {
			if (Boolean.TRUE.equals(processById(ids.get(i)))) {
				flagged++;
			}
			if (onProgress != null) {
				onProgress.accept(i + 1, ids.size());
			}
		}
		return new Summary(ids.size(), flagged);
	}

	/** Returns whether this activity ended up with a pending suggestion. */
	private Boolean processById(String activityId) {
		return tx.execute(status -> {
			Activity activity = activityRepository.findById(activityId).orElse(null);
			if (activity == null) {
				return false;
			}
			List<Record> records = recordRepository.findByActivityIdOrderByT(activityId);
			List<Integer> powerSeries = records.stream().map(Record::getPower).toList();
			List<Integer> tSeries = records.stream().map(Record::getT).toList();
			List<Double> distanceKmSeries = records.stream().map(Record::getDistanceKm).toList();

			thresholdDetectionService.detect(activity, powerSeries, tSeries, distanceKmSeries);
			activity.setThresholdChecked(true);
			activityRepository.save(activity);

			return activity.getSuggestedFtp() != null || activity.getSuggestedCriticalRunPower() != null
					|| (activity.getSuggestedThresholdPace() != null && !activity.getSuggestedThresholdPace().isEmpty());
		});
	}

	// detect() only ever applies to bike/run - every other sport is a guaranteed no-op, so
	// candidates are always restricted to those two regardless of an explicit sport filter.
	private Specification<Activity> candidateSpec(String athleteId, Sport sportFilter, LocalDate after, LocalDate before) {
		return (root, query, cb) -> {
			var predicate = cb.and(
					cb.equal(root.get("athlete").get("id"), athleteId),
					cb.isNull(root.get("parentActivity")),
					root.get("sport").in(Sport.BIKE, Sport.RUN));
			if (sportFilter != null) {
				predicate = cb.and(predicate, cb.equal(root.get("sport"), sportFilter));
			}
			if (after != null) {
				Instant afterInstant = after.atStartOfDay(ZoneOffset.UTC).toInstant();
				predicate = cb.and(predicate, cb.greaterThanOrEqualTo(root.get("startDate"), afterInstant));
			}
			if (before != null) {
				Instant beforeInstant = before.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
				predicate = cb.and(predicate, cb.lessThan(root.get("startDate"), beforeInstant));
			}
			return predicate;
		};
	}
}
