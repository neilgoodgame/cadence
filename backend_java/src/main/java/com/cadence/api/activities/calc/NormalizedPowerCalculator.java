package com.cadence.api.activities.calc;

import java.util.ArrayList;
import java.util.List;

/** 30-second rolling average of power, then the fourth-root of the mean of the rolling values raised to the fourth power. */
public final class NormalizedPowerCalculator {

	private static final int WINDOW = 30;

	public static Double compute(List<Integer> powerSeries) {
		if (powerSeries.isEmpty()) {
			return null;
		}
		int n = powerSeries.size();
		int[] values = new int[n];
		for (int i = 0; i < n; i++) {
			Integer p = powerSeries.get(i);
			values[i] = p != null ? p : 0;
		}
		if (n < WINDOW) {
			double sum = 0;
			for (int v : values) {
				sum += v;
			}
			return sum / n;
		}

		List<Double> rolling = new ArrayList<>(n - WINDOW + 1);
		long windowSum = 0;
		for (int i = 0; i < WINDOW; i++) {
			windowSum += values[i];
		}
		rolling.add(windowSum / (double) WINDOW);
		for (int i = WINDOW; i < n; i++) {
			windowSum += values[i] - values[i - WINDOW];
			rolling.add(windowSum / (double) WINDOW);
		}

		double sumFourth = 0;
		for (double r : rolling) {
			sumFourth += r * r * r * r;
		}
		double meanFourth = sumFourth / rolling.size();
		return Math.pow(meanFourth, 0.25);
	}

	private NormalizedPowerCalculator() {
	}
}
