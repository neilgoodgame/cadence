package com.cadence.api.athletes;

import java.time.LocalDate;
import java.util.Map;
import java.util.TreeMap;

/**
 * The load term for threshold decay (issue #485, docs/plans/threshold-decay.md section 2.3):
 * intensity-filtered training load, measured against the anchor, feeding {@link
 * ThresholdDecay#simulate}'s {@code loadByDay} input. Pure and static-final, like {@link
 * ThresholdDecay} and {@code TssCalculator} - reads only the per-activity histograms {@code
 * ActivityIntensityHistogramService} already builds, no Record re-reads needed, which is exactly
 * why that histogram stores seconds-per-watt rather than a raw series. Matches the Python
 * backend's athletes/decay_load.py.
 */
public final class DecayLoad {

	/** How far above Z2 counts toward the load (0.75 x anchor) - resolved open question 9 of the
	 * design doc: ship the hard cutoff, not the smooth-ramp alternative; the histogram means
	 * switching later needs no reprocessing. */
	public static final double DEFAULT_CUTOFF = 0.75;

	/** HR fallback cutoff for activities without trusted power - proposed, uncalibrated (open
	 * question 8); used as-is per the doc's own recommendation to check it against real HR+power
	 * rides before this feeds anything user-visible, which is deferred to a later slice. */
	public static final double DEFAULT_HR_CUTOFF = 0.85;

	public static final int LOAD_EWMA_DAYS = 42;

	/** One activity's contribution to the decay load - {@code Sigma_watt seconds_at_watt *
	 * w(watt/R) * (watt/R)^2 * 100/3600} (the section 2.3 formula, computed directly from the
	 * histogram rather than a raw series), falling back to the heart-rate histogram against
	 * {@code lthr} only when there's no usable power histogram. */
	public static double activityLoad(
			Map<Integer, Integer> power30sHistogram, Map<Integer, Integer> heartrateHistogram,
			Double anchorValue, Double lthr, double cutoff, double hrCutoff) {
		if (power30sHistogram != null && !power30sHistogram.isEmpty() && anchorValue != null && anchorValue != 0) {
			double total = 0;
			for (Map.Entry<Integer, Integer> entry : power30sHistogram.entrySet()) {
				double ratio = entry.getKey() / anchorValue;
				if (ratio >= cutoff) {
					total += ratio * ratio * 100 / 3600 * entry.getValue();
				}
			}
			return total;
		}
		if (heartrateHistogram != null && !heartrateHistogram.isEmpty() && lthr != null && lthr != 0) {
			double total = 0;
			for (Map.Entry<Integer, Integer> entry : heartrateHistogram.entrySet()) {
				double ratio = entry.getKey() / lthr;
				if (ratio >= hrCutoff) {
					total += ratio * ratio * 100 / 3600 * entry.getValue();
				}
			}
			return total;
		}
		return 0.0;
	}

	public static double activityLoad(
			Map<Integer, Integer> power30sHistogram, Map<Integer, Integer> heartrateHistogram, Double anchorValue, Double lthr) {
		return activityLoad(power30sHistogram, heartrateHistogram, anchorValue, lthr, DEFAULT_CUTOFF, DEFAULT_HR_CUTOFF);
	}

	/** The 42-day EWMA of daily load - same recurrence as {@code FitnessService}'s CTL ({@code x
	 * += (new - x) / days}, walked day by day from a true 0 baseline), duplicated locally rather
	 * than reused: that service is hardwired to TSS-by-day queries, not a reusable EWMA(map, days)
	 * helper (matches this codebase's own convention of small local duplicates over reaching into
	 * another class's implementation detail - see {@code ThresholdHistoryCalculator}'s and {@code
	 * TssCalculator}'s own Javadoc for the same call). Walks every day from the earliest to the
	 * latest key in {@code dailyLoads}, treating a missing day as zero load. */
	public static Map<LocalDate, Double> decayLoadSeries(Map<LocalDate, Double> dailyLoads) {
		Map<LocalDate, Double> result = new TreeMap<>();
		if (dailyLoads.isEmpty()) {
			return result;
		}
		LocalDate start = dailyLoads.keySet().stream().min(LocalDate::compareTo).orElseThrow();
		LocalDate end = dailyLoads.keySet().stream().max(LocalDate::compareTo).orElseThrow();
		double load = 0.0;
		for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
			load += (dailyLoads.getOrDefault(d, 0.0) - load) / LOAD_EWMA_DAYS;
			result.put(d, load);
		}
		return result;
	}

	private DecayLoad() {
	}
}
