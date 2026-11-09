package com.cadence.api.athletes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.cadence.api.activities.ActivityIntensityHistogramService;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/** Plan section 9.1's "Load filter"/"no circularity"/"histogram" scenarios (docs/plans/
 * threshold-decay.md), deferred from slice 1 until the histogram existed to feed them. Mirrors
 * the Python backend's athletes/test_decay_load.py. */
class DecayLoadTest {

	@Test
	void sampleAtExactlyCutoffCountsAndJustBelowDoesNot() {
		double r = 260.0;
		assertThat(DecayLoad.activityLoad(Map.of(195, 1), null, r, null)).isGreaterThan(0.0);
		assertThat(DecayLoad.activityLoad(Map.of(194, 1), null, r, null)).isEqualTo(0.0);
	}

	@Test
	void steadyRideBelowCutoffContributesNothing() {
		double r = 260.0;
		int watt = (int) Math.round(0.70 * r);
		assertThat(DecayLoad.activityLoad(Map.of(watt, 7200), null, r, null)).isEqualTo(0.0);
	}

	@Test
	void steadyRideAboveCutoffMatchesHandComputedValue() {
		double r = 260.0;
		int watt = (int) Math.round(0.80 * r);
		assertThat(DecayLoad.activityLoad(Map.of(watt, 3600), null, r, null)).isCloseTo(64.0, within(0.1));
	}

	@Test
	void hardCutoffStepsAtTheBoundary() {
		double r = 260.0;
		double justBelow = DecayLoad.activityLoad(Map.of(194, 100), null, r, null);
		double atCutoff = DecayLoad.activityLoad(Map.of(195, 100), null, r, null);
		double justAbove = DecayLoad.activityLoad(Map.of(196, 100), null, r, null);
		assertThat(justBelow).isEqualTo(0.0);
		assertThat(atCutoff).isGreaterThan(0.0);
		assertThat(justAbove).isGreaterThan(atCutoff);
	}

	@Test
	void hrFallbackOnlyAppliesWithoutAUsablePowerHistogram() {
		double lthr = 160.0;
		assertThat(DecayLoad.activityLoad(null, Map.of(145, 3600), 260.0, lthr)).isGreaterThan(0.0);
		assertThat(DecayLoad.activityLoad(Map.of(200, 10), Map.of(145, 3600), 260.0, lthr))
				.isEqualTo(DecayLoad.activityLoad(Map.of(200, 10), null, 260.0, lthr));
	}

	@Test
	void noReferenceValueGivesZero() {
		assertThat(DecayLoad.activityLoad(Map.of(200, 3600), null, null, null)).isEqualTo(0.0);
		assertThat(DecayLoad.activityLoad(Map.of(200, 3600), null, 0.0, null)).isEqualTo(0.0);
	}

	@Test
	void loadIsUnaffectedByUnrelatedComputationsOnTheSameHistogram() {
		Map<Integer, Integer> histogram = Map.of(195, 3600);
		double before = DecayLoad.activityLoad(histogram, null, 260.0, null);
		double unrelated = histogram.get(195) / 300.0; // an unrelated "IF-like" computation
		assertThat(unrelated).isGreaterThan(0); // keep the computation from being optimized away
		double after = DecayLoad.activityLoad(histogram, null, 260.0, null);
		assertThat(after).isEqualTo(before);
	}

	@Test
	void loadChangesOnlyWhenTheAnchorChanges() {
		Map<Integer, Integer> histogram = Map.of(220, 3600);
		double at260 = DecayLoad.activityLoad(histogram, null, 260.0, null);
		double at260Again = DecayLoad.activityLoad(histogram, null, 260.0, null);
		double at240 = DecayLoad.activityLoad(histogram, null, 240.0, null);
		assertThat(at260).isEqualTo(at260Again);
		assertThat(at260).isNotEqualTo(at240);
	}

	@Test
	void activityLoadFromHistogramMatchesADirectSeriesComputation() {
		double r = 260.0;
		List<Integer> powerSeries = new java.util.ArrayList<>();
		for (int i = 0; i < 40; i++) powerSeries.add(200);
		for (int i = 0; i < 40; i++) powerSeries.add(230);

		ActivityIntensityHistogramService.HistogramPair histograms =
				ActivityIntensityHistogramService.buildHistogram(powerSeries, List.of());

		List<Double> smoothed = com.cadence.api.activities.calc.NormalizedPowerCalculator.rollingMean(powerSeries, 30);
		double directLoad = 0.0;
		for (double value : smoothed) {
			double ratio = Math.round(value) / r;
			if (ratio >= 0.75) {
				directLoad += ratio * ratio * 100 / 3600;
			}
		}

		double histogramLoad = DecayLoad.activityLoad(histograms.power30s(), null, r, null);
		assertThat(histogramLoad).isCloseTo(directLoad, within(1e-9));
	}

	@Test
	void emptyInputGivesEmptyOutput() {
		assertThat(DecayLoad.decayLoadSeries(Map.of())).isEmpty();
	}

	@Test
	void walksEveryDayFromEarliestToLatestKey() {
		LocalDate d0 = LocalDate.of(2026, 1, 1);
		Map<LocalDate, Double> daily = new TreeMap<>();
		daily.put(d0, 100.0);
		daily.put(d0.plusDays(2), 100.0);
		Map<LocalDate, Double> series = DecayLoad.decayLoadSeries(daily);
		assertThat(series.keySet()).containsExactly(d0, d0.plusDays(1), d0.plusDays(2));
	}

	@Test
	void recurrenceMatchesHandComputedEwma() {
		LocalDate d0 = LocalDate.of(2026, 1, 1);
		Map<LocalDate, Double> series = DecayLoad.decayLoadSeries(Map.of(d0, 42.0));
		assertThat(series.get(d0)).isCloseTo(42.0 / 42, within(1e-9));
	}

	@Test
	void maintainedLoadConvergesTowardTheSteadyValue() {
		LocalDate d0 = LocalDate.of(2026, 1, 1);
		Map<LocalDate, Double> daily = new TreeMap<>();
		for (int i = 0; i < 400; i++) {
			daily.put(d0.plusDays(i), 10.0);
		}
		Map<LocalDate, Double> series = DecayLoad.decayLoadSeries(daily);
		assertThat(series.get(d0.plusDays(399))).isCloseTo(10.0, within(0.01));
	}
}
