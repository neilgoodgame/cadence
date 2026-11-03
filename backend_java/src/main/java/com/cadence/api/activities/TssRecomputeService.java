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
import java.util.Objects;
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
		rate(activity, athlete);
		activityRepository.save(activity);
		return activity.getTss();
	}

	@Transactional
	public int recomputeForAthlete(User athlete) {
		List<Activity> activities = activityRepository.findRecomputeCandidates(athlete.getId());
		int updated = 0;
		for (Activity activity : activities) {
			if (rate(activity, athlete)) {
				activityRepository.save(activity);
				updated++;
			}
		}
		return updated;
	}

	/** Computes and writes {@code intensity} and {@code tss} together from this activity's own
	 * stored Record rows - used by ingest ({@code ComputeDerivedStatsTasklet}), "Recompute TSS"
	 * (above) and "Recompute stats" ({@code DerivedStatsRecomputeService}) alike, so the three
	 * can't drift apart (see docs/plans/threshold-decay.md section 8 - issue #481: "Recompute
	 * stats" used to update intensity but never tss). Returns whether either value changed. Does
	 * NOT touch avgPower/normPower/other derived stats - those stay exactly where they are
	 * today. */
	public boolean rate(Activity activity, User athlete) {
		// Reads the ThresholdHistory entry effective as of the activity's own date, not the
		// athlete's current profile - so this stays historically accurate whether called at
		// ingest or from an explicit recompute action (single-activity, bulk-per-athlete, or the
		// post-threshold-edit re-rate hook). Falls back to the athlete's live profile value when
		// no ledger entry is effective yet (e.g. before any qualifying effort exists).
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

		boolean changed = false;

		Double newIntensity = null;
		if (normPower != null && normPower > 0 && thresholdPowerDouble != null
				&& (activity.getSport() == Sport.BIKE || activity.getSport() == Sport.RUN)) {
			newIntensity = Math.round(normPower / thresholdPowerDouble * 1000) / 1000.0;
		}
		if (!Objects.equals(newIntensity, activity.getIntensity())) {
			activity.setIntensity(newIntensity);
			changed = true;
		}

		Integer tss = TssCalculator.powerBased(normPower, thresholdPower, activity.getMovingTime());
		if (tss == null) {
			ZoneSet hrZoneSet = zoneService.getOrCreate(athlete, ZoneType.HEART_RATE);
			Double hrThreshold = zoneService.referenceFor(athlete, ZoneType.HEART_RATE);
			List<Integer> hrSeries = records.stream().map(Record::getHeartrate).toList();
			Map<String, Integer> secondsPerZone = TssCalculator.secondsPerZone(hrSeries, hrZoneSet.getZones(), hrThreshold);
			tss = TssCalculator.hrBased(secondsPerZone, hrZoneSet.getZones());
		}
		if (!Objects.equals(tss, activity.getTss())) {
			activity.setTss(tss);
			changed = true;
		}

		return changed;
	}

	private Double referenceForOrLive(User athlete, ZoneType type, Activity activity) {
		Double value = zoneService.referenceFor(athlete, type, activity);
		return value != null ? value : zoneService.referenceFor(athlete, type);
	}
}
