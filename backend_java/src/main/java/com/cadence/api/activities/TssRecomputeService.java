package com.cadence.api.activities;

import com.cadence.api.activities.calc.NormalizedPowerCalculator;
import com.cadence.api.activities.calc.RunningPowerSanitizer;
import com.cadence.api.activities.calc.TssCalculator;
import com.cadence.api.athletes.ZoneService;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.athletes.ZoneSet;
import com.cadence.api.athletes.ZoneType;
import com.cadence.api.users.User;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TssRecomputeService {

	private final ActivityRepository activityRepository;
	private final RecordRepository recordRepository;
	private final ZoneService zoneService;

	public TssRecomputeService(ActivityRepository activityRepository, RecordRepository recordRepository,
			ZoneService zoneService) {
		this.activityRepository = activityRepository;
		this.recordRepository = recordRepository;
		this.zoneService = zoneService;
	}

	@Transactional
	public int recomputeForActivity(String activityId) {
		// Reload within this transaction — the entity passed from the controller is
		// detached (its session closed after getActivity returned), so lazy-loading
		// activity.getAthlete() would throw LazyInitializationException otherwise.
		Activity activity = activityRepository.findById(activityId)
				.orElseThrow(() -> new com.cadence.api.common.error.NotFoundException("No such activity."));
		User athlete = activity.getAthlete();
		ZoneSet hrZoneSet = zoneService.getOrCreate(athlete, ZoneType.HEART_RATE);
		Double hrThreshold = zoneService.referenceFor(athlete, ZoneType.HEART_RATE);
		int tss = computeTss(activity, athlete, hrZoneSet, hrThreshold);
		activity.setTss(tss);
		activityRepository.save(activity);
		return tss;
	}

	@Transactional
	public int recomputeForAthlete(User athlete) {
		ZoneSet hrZoneSet = zoneService.getOrCreate(athlete, ZoneType.HEART_RATE);
		Double hrThreshold = zoneService.referenceFor(athlete, ZoneType.HEART_RATE);

		List<Activity> activities = activityRepository.findRecomputeCandidates(athlete.getId());
		int updated = 0;
		for (Activity activity : activities) {
			int newTss = computeTss(activity, athlete, hrZoneSet, hrThreshold);
			if (newTss != activity.getTss()) {
				activity.setTss(newTss);
				activityRepository.save(activity);
				updated++;
			}
		}
		return updated;
	}

	private int computeTss(Activity activity, User athlete, ZoneSet hrZoneSet, Double hrThreshold) {
		// Reads the ThresholdHistory entry effective as of the activity's own date, not the
		// athlete's current profile - so this stays historically accurate whether called at
		// ingest or from an explicit "Recompute TSS" action (both single-activity and
		// bulk-per-athlete). Falls back to the athlete's live profile value when no ledger entry
		// is effective yet (e.g. before any qualifying effort exists) - same convention as
		// ComputeDerivedStatsTasklet. `athlete` is still needed below for the HR-based fallback,
		// which has no ledger equivalent (lthr isn't rolling-window derived).
		Double thresholdPowerDouble = switch (activity.getSport()) {
			case BIKE -> referenceForOrLive(athlete, ZoneType.BIKE_POWER, activity);
			case RUN -> referenceForOrLive(athlete, ZoneType.RUN_POWER, activity);
			default -> null;
		};
		Integer thresholdPower = thresholdPowerDouble != null ? (int) Math.round(thresholdPowerDouble) : null;

		// Load records once; recompute normPower from stored per-second power rather than the
		// activity-level stat (which may be corrupt if Garmin's native power field overrode
		// the Stryd developer field during upload, inflating the value 10–15x).
		List<Record> records = recordRepository.findByActivityIdOrderByT(activity.getId());
		List<Integer> powerSeries = RunningPowerSanitizer.sanitize(
				records.stream().map(Record::getPower).toList(), activity.getSport(), athlete.getMaxRunningPowerWatts());
		// This run's power came from the source the athlete currently excludes - score it
		// against the HR-based fallback below instead of a power-based threshold it doesn't
		// trust, same reasoning TssCalculator's own >1.5-intensity guard exists for.
		boolean excludedRunningPower = activity.getSport() == Sport.RUN && !activity.matchesRunningPowerPreference(athlete);
		Double normPower = !excludedRunningPower && powerSeries.stream().anyMatch(p -> p != null)
				? NormalizedPowerCalculator.compute(powerSeries) : null;

		Integer tss = TssCalculator.powerBased(normPower, thresholdPower, activity.getMovingTime());
		if (tss != null) {
			return tss;
		}

		List<Integer> hrSeries = records.stream().map(Record::getHeartrate).toList();
		Map<String, Integer> secondsPerZone = TssCalculator.secondsPerZone(hrSeries, hrZoneSet.getZones(), hrThreshold);
		return TssCalculator.hrBased(secondsPerZone, hrZoneSet.getZones());
	}

	private Double referenceForOrLive(User athlete, ZoneType type, Activity activity) {
		Double value = zoneService.referenceFor(athlete, type, activity);
		return value != null ? value : zoneService.referenceFor(athlete, type);
	}
}
