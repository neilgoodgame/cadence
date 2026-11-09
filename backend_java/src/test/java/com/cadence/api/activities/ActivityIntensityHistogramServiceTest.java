package com.cadence.api.activities;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The pure half of ActivityIntensityHistogramService - buildHistogram - needs no Spring
 * context. Mirrors the Python backend's activities/tests/test_intensity_histogram.py::
 * BuildHistogramTests. The DB-touching rebuild/ensure half is covered by
 * ActivityIntensityHistogramServiceIntegrationTest. */
class ActivityIntensityHistogramServiceTest {

	private static List<Integer> constant(int value, int n) {
		return Collections.nCopies(n, value);
	}

	@Test
	void talliesSecondsPerWholeWatt() {
		// 30 identical samples collapse to one 30s-smoothed point -> one bucket, one second.
		var histograms = ActivityIntensityHistogramService.buildHistogram(constant(200, 30), List.of());
		assertThat(histograms.power30s()).containsExactly(java.util.Map.entry(200, 1));
	}

	@Test
	void noPowerDataGivesNull() {
		var histograms = ActivityIntensityHistogramService.buildHistogram(Arrays.asList(null, null), Arrays.asList(null, null));
		assertThat(histograms.power30s()).isNull();
		assertThat(histograms.heartrate()).isNull();
	}

	@Test
	void heartrateIsTalliedRawNotSmoothed() {
		var histograms = ActivityIntensityHistogramService.buildHistogram(List.of(), Arrays.asList(150, 150, 160));
		assertThat(histograms.heartrate()).containsOnly(java.util.Map.entry(150, 2), java.util.Map.entry(160, 1));
	}

	@Test
	void shorterThanWindowCollapsesToOneBucket() {
		var histograms = ActivityIntensityHistogramService.buildHistogram(constant(185, 5), List.of());
		assertThat(histograms.power30s()).containsExactly(java.util.Map.entry(185, 1));
	}
}
