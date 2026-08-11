package com.cadence.api.activities.calc;

import java.util.List;

/**
 * The fastest pace over any contiguous span of an activity covering at least a target
 * distance - a classic minimum-window two-pointer scan, not a variant of
 * {@link DurationCurveCalculator}: a fixed *distance* target needs a variable-length *time*
 * window, the opposite shape of a fixed-duration best-effort.
 *
 * <p>Takes tSeries (each sample's real elapsed-seconds offset) rather than assuming samples
 * are 1 Hz - some devices ("smart"/adaptive recording) log a sample every few seconds
 * instead of every second, and using the sample <em>index</em> gap as a stand-in for elapsed
 * time silently understates duration (and so overstates pace) on those files.
 */
public final class PaceBestEffortCalculator {

	public static Double bestPaceSecondsPerKm(List<Integer> tSeries, List<Double> distanceKmSeries, double targetKm) {
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
			int duration = tSeries.get(right) - tSeries.get(left);
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

	/**
	 * The fastest pace sustained over any contiguous span of an activity lasting at least
	 * targetSeconds - the dual of {@link #bestPaceSecondsPerKm} (fixed <em>time</em> target,
	 * variable <em>distance</em> window, the opposite shape - same two-pointer scan, same
	 * forward-fill-cumulative-distance convention, just swapping which axis is the fixed
	 * target). Used to detect a possible new threshold pace from a sustained effort (e.g.
	 * ~1 hour), mirroring {@link DurationCurveCalculator#bestAverage}'s duration-based windows
	 * for power/HR, which pace can't use directly since pace needs distance, not a flat
	 * per-sample value.
	 */
	public static Double bestPaceSecondsPerKmOverDuration(
			List<Integer> tSeries, List<Double> distanceKmSeries, int targetSeconds) {
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
			while (right < n && tSeries.get(right) - tSeries.get(left) < targetSeconds) {
				right++;
			}
			if (right >= n) {
				break;
			}
			int duration = tSeries.get(right) - tSeries.get(left);
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
