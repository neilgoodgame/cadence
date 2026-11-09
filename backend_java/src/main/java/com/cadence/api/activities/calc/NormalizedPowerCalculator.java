package com.cadence.api.activities.calc;

import java.util.ArrayList;
import java.util.List;

/** 30-second rolling average of power, then the fourth-root of the mean of the rolling values raised to the fourth power. */
public final class NormalizedPowerCalculator {

	public static final int WINDOW = 30;

	public static Double compute(List<Integer> powerSeries) {
		if (powerSeries.isEmpty()) {
			return null;
		}
		List<Double> rolling = rollingMean(powerSeries, WINDOW);
		double sumFourth = 0;
		for (double r : rolling) {
			sumFourth += r * r * r * r;
		}
		double meanFourth = sumFourth / rolling.size();
		return Math.pow(meanFourth, 0.25);
	}

	/** The 30s-smoothed power series {@link #compute} and {@code
	 * ActivityIntensityHistogramService} both need - zero-fills a missing sample, downsampled to
	 * one value per window position once the series is long enough ({@code n - window + 1}
	 * points), or a single whole-activity average if it's shorter than {@code window}. Shared so
	 * NP and the intensity histogram agree on what "30s-smoothed" means, rather than drifting
	 * into two independently-tuned definitions of the same thing. */
	public static List<Double> rollingMean(List<Integer> powerSeries, int window) {
		int n = powerSeries.size();
		if (n == 0) {
			return List.of();
		}
		int[] values = new int[n];
		for (int i = 0; i < n; i++) {
			Integer p = powerSeries.get(i);
			values[i] = p != null ? p : 0;
		}
		if (n < window) {
			double sum = 0;
			for (int v : values) {
				sum += v;
			}
			return List.of(sum / n);
		}

		List<Double> rolling = new ArrayList<>(n - window + 1);
		long windowSum = 0;
		for (int i = 0; i < window; i++) {
			windowSum += values[i];
		}
		rolling.add(windowSum / (double) window);
		for (int i = window; i < n; i++) {
			windowSum += values[i] - values[i - window];
			rolling.add(windowSum / (double) window);
		}
		return rolling;
	}

	private NormalizedPowerCalculator() {
	}
}
