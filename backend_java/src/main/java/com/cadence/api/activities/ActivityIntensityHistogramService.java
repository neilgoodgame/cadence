package com.cadence.api.activities;

import com.cadence.api.activities.calc.NormalizedPowerCalculator;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.users.User;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds and stores per-activity power/heart-rate histograms for the threshold-decay load term
 * (issue #485, docs/plans/threshold-decay.md section 2.3). Mirrors {@link
 * ActivitySectionsService}'s "own table, no row yet means it hasn't been built, lazy-build on
 * first read" pattern exactly - not something to redesign here. Matches the Python backend's
 * activities/intensity_histogram.py.
 */
@Service
public class ActivityIntensityHistogramService {

	private final RecordRepository recordRepository;
	private final ActivityIntensityHistogramRepository histogramRepository;

	public ActivityIntensityHistogramService(
			RecordRepository recordRepository, ActivityIntensityHistogramRepository histogramRepository) {
		this.recordRepository = recordRepository;
		this.histogramRepository = histogramRepository;
	}

	/** Seconds spent at each rounded value - each entry assumed to cover one second, the same
	 * ~1Hz assumption {@link NormalizedPowerCalculator} already makes (no new assumption
	 * introduced here). Empty with nothing to tally (every sample missing). */
	private static Map<Integer, Integer> tally(List<? extends Number> values) {
		Map<Integer, Integer> counts = new HashMap<>();
		for (Number v : values) {
			if (v == null) {
				continue;
			}
			int key = (int) Math.round(v.doubleValue());
			counts.merge(key, 1, Integer::sum);
		}
		return counts.isEmpty() ? null : counts;
	}

	public record HistogramPair(Map<Integer, Integer> power30s, Map<Integer, Integer> heartrate) {
	}

	/** Pure: `powerSeries`/`hrSeries` -> `(power30sHistogram, heartrateHistogram)`. The caller is
	 * responsible for any gating (e.g. a running-power-source exclusion) - this just tallies
	 * whatever series it's given. Power is smoothed first (via {@link
	 * NormalizedPowerCalculator#rollingMean}); heart rate is tallied raw - the design doc only
	 * calls for smoothing on the power side. */
	public static HistogramPair buildHistogram(List<Integer> powerSeries, List<Integer> hrSeries) {
		boolean anyPower = powerSeries.stream().anyMatch(p -> p != null);
		Map<Integer, Integer> powerHist = anyPower
				? tally(NormalizedPowerCalculator.rollingMean(powerSeries, NormalizedPowerCalculator.WINDOW))
				: null;
		Map<Integer, Integer> hrHist = tally(hrSeries);
		return new HistogramPair(powerHist, hrHist);
	}

	/** (Re)builds and saves the activity's intensity histogram. `powerSeries`/`hrSeries` should
	 * already be gated for running-power-source exclusion if applicable, matching every other
	 * {@code NormalizedPowerCalculator.compute} caller's own convention of gating before the
	 * call. */
	@Transactional
	public ActivityIntensityHistogram rebuildHistogram(Activity activity, List<Integer> powerSeries, List<Integer> hrSeries) {
		HistogramPair histograms = buildHistogram(powerSeries, hrSeries);
		ActivityIntensityHistogram histogram = histogramRepository.findById(activity.getId())
				.orElseGet(() -> new ActivityIntensityHistogram(activity.getId()));
		histogram.setPower30s(histograms.power30s());
		histogram.setHeartrate(histograms.heartrate());
		return histogramRepository.save(histogram);
	}

	/** The stored histogram, built on first read - fetches and gates its own records since
	 * there's no external caller to do it in this path (unlike {@link #rebuildHistogram}'s other
	 * two call sites, which already have a gated series at hand). */
	@Transactional
	public ActivityIntensityHistogram ensureHistogram(Activity activity) {
		return histogramRepository.findById(activity.getId()).orElseGet(() -> {
			User athlete = activity.getAthlete();
			List<RecordRepository.PowerHeartrate> rows =
					recordRepository.findPowerHeartrateByActivityIdOrderByT(activity.getId());
			List<Integer> powerSeries = rows.stream().map(RecordRepository.PowerHeartrate::getPower).toList();
			if (activity.getSport() == Sport.RUN && !activity.matchesRunningPowerPreference(athlete)) {
				powerSeries = Collections.nCopies(powerSeries.size(), null);
			}
			List<Integer> hrSeries = rows.stream().map(RecordRepository.PowerHeartrate::getHeartrate).toList();
			return rebuildHistogram(activity, powerSeries, hrSeries);
		});
	}
}
