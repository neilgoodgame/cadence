package com.cadence.api.activities.calc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Sliding-window best average over a series (power or heart rate), for duration curves and best-effort windows alike. */
public final class DurationCurveCalculator {

	/** Best average over exactly {@code window} samples, or {@code null} if the series is shorter than the window. */
	public static Double bestAverage(List<Integer> series, int window) {
		int n = series.size();
		if (window > n || window <= 0) {
			return null;
		}
		long windowSum = 0;
		for (int i = 0; i < window; i++) {
			windowSum += orZero(series.get(i));
		}
		double best = windowSum / (double) window;
		for (int i = window; i < n; i++) {
			windowSum += orZero(series.get(i)) - orZero(series.get(i - window));
			double avg = windowSum / (double) window;
			if (avg > best) {
				best = avg;
			}
		}
		return best;
	}

	/** A point per duration that the series is at least as long as, e.g. {@code {5: 712.0, 300: 351.0}}. */
	public static Map<Integer, Double> compute(List<Integer> series, List<Integer> durations) {
		Map<Integer, Double> points = new LinkedHashMap<>();
		for (int duration : durations) {
			Double best = bestAverage(series, duration);
			if (best != null) {
				points.put(duration, round1(best));
			}
		}
		return points;
	}

	private static int orZero(Integer v) {
		return v != null ? v : 0;
	}

	private static double round1(double v) {
		return Math.round(v * 10) / 10.0;
	}

	private DurationCurveCalculator() {
	}
}
