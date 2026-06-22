package com.cadence.api.activities.calc;

import java.util.List;

/**
 * The fastest pace over any contiguous span of an activity covering at least a target
 * distance - a classic minimum-window two-pointer scan, not a variant of
 * {@link DurationCurveCalculator}: a fixed *distance* target needs a variable-length *time*
 * window, the opposite shape of a fixed-duration best-effort.
 */
public final class PaceBestEffortCalculator {

	public static Double bestPaceSecondsPerKm(List<Double> distanceKmSeries, double targetKm) {
		// Forward-fill: a null sample (e.g. a brief GPS dropout) means "no new distance
		// recorded yet," not "reset to zero" - the same convention the activity's total
		// distance already relies on.
		int n = distanceKmSeries.size();
		double[] cumulative = new double[n];
		double last = 0.0;
		for (int i = 0; i < n; i++) {
			Double d = distanceKmSeries.get(i);
			if (d != null) {
				last = d;
			}
			cumulative[i] = last;
		}

		Double best = null;
		int left = 0;
		int right = 0;
		while (left < n) {
			if (right < left) {
				right = left;
			}
			while (right < n && cumulative[right] - cumulative[left] < targetKm) {
				right++;
			}
			if (right >= n) {
				break;
			}
			int duration = right - left;
			double actualDistance = cumulative[right] - cumulative[left];
			if (duration > 0 && actualDistance > 0) {
				double pace = duration / actualDistance;
				if (best == null || pace < best) {
					best = pace;
				}
			}
			left++;
		}
		return best;
	}

	private PaceBestEffortCalculator() {
	}
}
