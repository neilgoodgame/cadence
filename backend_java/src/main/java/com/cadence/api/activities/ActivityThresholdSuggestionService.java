package com.cadence.api.activities;

import com.cadence.api.activities.calc.NormalizedPowerCalculator;
import com.cadence.api.activities.calc.TssCalculator;
import com.cadence.api.athletes.ZoneSet;
import com.cadence.api.athletes.ZoneService;
import com.cadence.api.athletes.ZoneType;
import com.cadence.api.common.error.ConflictException;
import com.cadence.api.common.error.NotFoundException;
import com.cadence.api.common.error.ValidationException;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Accept or dismiss a threshold increase detected from an activity's own best effort (see
 * {@link ThresholdDetectionService}). Accepting updates the athlete's profile <em>and</em> this
 * activity's own snapshot (the effort that revealed the new threshold is itself re-rated
 * against it, not just future activities), then recomputes this activity's TSS/intensity so its
 * own numbers reflect the change too - skipped for thresholdPace, which neither TSS nor
 * intensity is derived from anywhere in this codebase. Dismissing just clears the suggestion.
 */
@Service
public class ActivityThresholdSuggestionService {

	/** field name -> (suggested-value getter, snapshot setter, athlete-profile setter, suggested-clearer) */
	private record FieldOps(Function<Activity, Object> suggestedGetter, BiConsumer<Activity, Object> snapshotSetter,
			BiConsumer<User, Object> athleteSetter, java.util.function.Consumer<Activity> suggestedClearer) {
	}

	private static final Map<String, FieldOps> FIELD_OPS = Map.of(
			"ftp", new FieldOps(
					Activity::getSuggestedFtp,
					(a, v) -> a.setFtpSnapshot((Integer) v),
					(u, v) -> u.setFtp((Integer) v),
					a -> a.setSuggestedFtp(null)),
			"critical_run_power", new FieldOps(
					Activity::getSuggestedCriticalRunPower,
					(a, v) -> a.setCriticalRunPowerSnapshot((Integer) v),
					(u, v) -> u.setCriticalRunPower((Integer) v),
					a -> a.setSuggestedCriticalRunPower(null)),
			"threshold_pace", new FieldOps(
					Activity::getSuggestedThresholdPace,
					(a, v) -> a.setThresholdPaceSnapshot((String) v),
					(u, v) -> u.setThresholdPace((String) v),
					a -> a.setSuggestedThresholdPace("")));

	private final ActivityRepository activityRepository;
	private final RecordRepository recordRepository;
	private final UserRepository userRepository;
	private final ZoneService zoneService;

	public ActivityThresholdSuggestionService(ActivityRepository activityRepository, RecordRepository recordRepository,
			UserRepository userRepository, ZoneService zoneService) {
		this.activityRepository = activityRepository;
		this.recordRepository = recordRepository;
		this.userRepository = userRepository;
		this.zoneService = zoneService;
	}

	@Transactional
	public Activity apply(String activityId, String field, boolean accept) {
		Activity activity = activityRepository.findById(activityId)
				.orElseThrow(() -> new NotFoundException("No such activity."));
		FieldOps ops = FIELD_OPS.get(field);
		if (ops == null) {
			throw new ValidationException("Must be one of ftp, critical_run_power, threshold_pace.", "field");
		}
		Object suggestedValue = ops.suggestedGetter().apply(activity);
		boolean hasSuggestion = suggestedValue != null && !"".equals(suggestedValue);
		if (!hasSuggestion) {
			throw new ConflictException("No pending suggestion for that field.");
		}

		if (accept) {
			User athlete = activity.getAthlete();
			ops.athleteSetter().accept(athlete, suggestedValue);
			userRepository.save(athlete);
			ops.snapshotSetter().accept(activity, suggestedValue);

			if (!"threshold_pace".equals(field)) {
				recomputeTssAndIntensity(activity, athlete);
			}
		}

		ops.suggestedClearer().accept(activity);
		return activityRepository.save(activity);
	}

	private void recomputeTssAndIntensity(Activity activity, User athlete) {
		List<Record> records = recordRepository.findByActivityIdOrderByT(activity.getId());
		List<Integer> powerSeries = records.stream().map(Record::getPower).toList();
		Double normPower = powerSeries.stream().anyMatch(p -> p != null)
				? NormalizedPowerCalculator.compute(powerSeries) : null;

		Integer threshold = switch (activity.getSport()) {
			case BIKE -> activity.getFtpSnapshot();
			case RUN -> activity.getCriticalRunPowerSnapshot();
			default -> null;
		};
		if (normPower != null && normPower > 0 && threshold != null) {
			activity.setIntensity(Math.round(normPower / threshold * 1000) / 1000.0);
		}

		Integer tss = TssCalculator.powerBased(normPower, threshold, activity.getMovingTime());
		if (tss == null) {
			ZoneSet hrZoneSet = zoneService.getOrCreate(athlete, ZoneType.HEART_RATE);
			Double hrThreshold = zoneService.referenceFor(athlete, ZoneType.HEART_RATE);
			List<Integer> hrSeries = records.stream().map(Record::getHeartrate).toList();
			Map<String, Integer> secondsPerZone = TssCalculator.secondsPerZone(hrSeries, hrZoneSet.getZones(), hrThreshold);
			tss = TssCalculator.hrBased(secondsPerZone, hrZoneSet.getZones());
		}
		activity.setTss(tss);
	}
}
