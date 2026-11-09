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
import java.util.List;
import java.util.Map;
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

	public DecayLoadService(ActivityRepository activityRepository, ActivityIntensityHistogramService histogramService) {
		this.activityRepository = activityRepository;
		this.histogramService = histogramService;
	}

	private static Sport sportFor(ThresholdField field) {
		return field == ThresholdField.FTP ? Sport.BIKE : Sport.RUN;
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

		Map<LocalDate, Double> dailyLoads = new HashMap<>();
		for (Activity activity : activities) {
			ActivityIntensityHistogram histogram = histogramService.ensureHistogram(activity);
			double load = DecayLoad.activityLoad(histogram.getPower30s(), histogram.getHeartrate(), anchorValue, lthr);
			LocalDate activityDate = activity.getStartDate().atZone(ZoneOffset.UTC).toLocalDate();
			dailyLoads.merge(activityDate, load, Double::sum);
		}

		return DecayLoad.decayLoadSeries(dailyLoads);
	}
}
