package com.cadence.api.athletes;

import com.cadence.api.activities.Activity;
import com.cadence.api.activities.ActivityIntensityHistogram;
import com.cadence.api.activities.ActivityIntensityHistogramService;
import com.cadence.api.activities.ActivityRepository;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.users.User;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * {@code loadByDay} for {@link ThresholdDecay#simulate}, for real - deliberately thin: read-only
 * (no locking, no writes beyond histogram lazy-backfill, which is already idempotent) and doesn't
 * decide *which* anchor value to measure against - the caller (deferred to the next #485 slice,
 * which wires this into the real recompute paths) passes in whatever anchor was in effect at the
 * relevant point in time, since that's a wiring-layer decision this service has no business
 * making. Matches the Python backend's athletes/decay_load.py::load_by_day_for_athlete.
 */
@Service
public class DecayLoadService {

	private final ActivityRepository activityRepository;
	private final ActivityIntensityHistogramService histogramService;
	private final ZoneService zoneService;
	private final ExcludedThresholdCandidateRepository excludedThresholdCandidateRepository;

	public DecayLoadService(ActivityRepository activityRepository, ActivityIntensityHistogramService histogramService,
			ZoneService zoneService, ExcludedThresholdCandidateRepository excludedThresholdCandidateRepository) {
		this.activityRepository = activityRepository;
		this.histogramService = histogramService;
		this.zoneService = zoneService;
		this.excludedThresholdCandidateRepository = excludedThresholdCandidateRepository;
	}

	private static Sport sportFor(ThresholdField field) {
		return field == ThresholdField.FTP ? Sport.BIKE : Sport.RUN;
	}

	private static ZoneType zoneTypeFor(ThresholdField field) {
		return switch (field) {
			case FTP -> ZoneType.BIKE_POWER;
			case CRITICAL_RUN_POWER -> ZoneType.RUN_POWER;
			case THRESHOLD_PACE -> ZoneType.PACE;
		};
	}

	/** Every qualifying activity (same sport/primaryActivity-is-null filter {@code
	 * ThresholdHistoryCalculator.currentWindowValue} already uses) in {@code [start, end]},
	 * summed per day via {@link DecayLoad#activityLoad} against the given anchor, then smoothed
	 * via {@link DecayLoad#decayLoadSeries}. */
	public Map<LocalDate, Double> loadByDayForAthlete(
			User athlete, ThresholdField field, Double anchorValue, Double lthr, LocalDate start, LocalDate end) {
		Instant startInstant = start.atStartOfDay(ZoneOffset.UTC).toInstant();
		Instant endInstant = end.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
		List<Activity> activities =
				activityRepository.findForThresholdWindow(athlete.getId(), sportFor(field), startInstant, endInstant);

		// An activity excluded from threshold detection for this field (issue #616) has
		// untrustworthy power, so it counts through its heart rate instead - activityLoad's own
		// fallback for an activity without usable power. The session still counts as training,
		// provided the athlete has an LTHR: without one the HR path has no reference, so it adds no
		// load, the same as any other activity without usable power.
		Set<String> excludedIds = new HashSet<>(
				excludedThresholdCandidateRepository.findActivityIdsByAthleteIdAndField(athlete.getId(), field));
		Map<LocalDate, Double> dailyLoads = new HashMap<>();
		for (Activity activity : activities) {
			ActivityIntensityHistogram histogram = histogramService.ensureHistogram(activity);
			Map<Integer, Integer> power = excludedIds.contains(activity.getId()) ? null : histogram.getPower30s();
			double load = DecayLoad.activityLoad(power, histogram.getHeartrate(), anchorValue, lthr);
			LocalDate activityDate = activity.getStartDate().atZone(ZoneOffset.UTC).toLocalDate();
			dailyLoads.merge(activityDate, load, Double::sum);
		}

		return DecayLoad.decayLoadSeries(dailyLoads);
	}

	/** Issue #487: {@link DecayLoad#activityLoad} for exactly one activity, resolving its own
	 * histogram and reference threshold - the per-activity number {@code
	 * listActivityAboveZ2Load} exposes directly, and the per-day building block {@code
	 * computeAboveZ2FitnessSeries} sums into a CTL/ATL series. {@code athlete} is taken
	 * explicitly, not read off {@code activity.getAthlete()} - this is called from a batch over
	 * many activities outside any one transaction, so a lazy association on an activity fetched
	 * in an earlier (already-closed) transaction would throw {@code LazyInitializationException}
	 * (same gotcha as backend_java/CLAUDE.md's "controller loads an entity, lazy associations
	 * can't be read" note); the caller already has the athlete loaded anyway. {@code reference}
	 * overrides the activity's own in-effect anchor ({@link ZoneService#referenceFor(User,
	 * ZoneType, Activity)}) when given, e.g. to ask "what if FTP had been X the whole time" for
	 * calibration - never applies to the HR fallback's own lthr, which (like {@link
	 * #loadByDayForAthlete}) always reads the athlete's current stored value. Returns null (not
	 * 0.0) when there's no histogram data at all, or no reference to measure it against - {@link
	 * DecayLoad#activityLoad} itself conflates either of those with "zero load above cutoff",
	 * this wrapper doesn't.
	 *
	 * <p>For {@code THRESHOLD_PACE}, referenceFor resolves a pace-in-seconds number - if the same
	 * activity also has a power histogram, activityLoad's power branch would divide watts by that
	 * pace number, which is nonsensical. This is pre-existing behavior {@link
	 * #loadByDayForAthlete} already has for this field (there's no pace-based histogram, and no
	 * power/pace cross-check anywhere in this class) - not something this wrapper introduces or
	 * fixes. */
	public Double aboveZ2LoadForActivity(Activity activity, User athlete, ThresholdField field, Double reference) {
		return aboveZ2LoadForActivity(activity, athlete, field, reference, excludedActivityIds(athlete, field));
	}

	/** The excluded-activity ids for (athlete, field), for a caller looping over many activities
	 * to load once and pass to {@link #aboveZ2LoadForActivity(Activity, User, ThresholdField,
	 * Double, Set)} instead of one query per activity. */
	public Set<String> excludedActivityIds(User athlete, ThresholdField field) {
		return new HashSet<>(excludedThresholdCandidateRepository.findActivityIdsByAthleteIdAndField(athlete.getId(), field));
	}

	public Double aboveZ2LoadForActivity(Activity activity, User athlete, ThresholdField field, Double reference,
			Set<String> excludedIds) {
		ActivityIntensityHistogram histogram = histogramService.ensureHistogram(activity);
		// Same HR fallback as loadByDayForAthlete for an activity excluded from this field's
		// threshold detection (issue #616).
		Map<Integer, Integer> power = excludedIds.contains(activity.getId()) ? null : histogram.getPower30s();
		if (power == null && histogram.getHeartrate() == null) {
			return null;
		}
		Double effectiveReference =
				reference != null ? reference : zoneService.referenceFor(athlete, zoneTypeFor(field), activity);
		if (effectiveReference == null || effectiveReference == 0.0) {
			return null;
		}
		Double lthr = athlete.getLthr() != null ? athlete.getLthr().doubleValue() : null;
		return DecayLoad.activityLoad(power, histogram.getHeartrate(), effectiveReference, lthr);
	}
}
