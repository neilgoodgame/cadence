package com.cadence.api.uploads;

import com.cadence.api.uploads.parsing.ParsedActivity;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Pure derivations from a parsed file's samples/laps that don't depend on the athlete's profile. */
public final class UploadCalculations {

	// ~30s at the 1Hz rate these files are stored at.
	private static final int ELEVATION_SMOOTHING_WINDOW = 30;

	private static final double KJ_PER_KCAL = 4.184;

	/** work_kJ / 0.24 (a standard cycling efficiency approximation) converts mechanical
	 * work into metabolic energy expenditure, still in kJ - dividing by KJ_PER_KCAL
	 * converts that into kcal. */
	public static int caloriesFromWorkKj(double workKj) {
		double metabolicKj = workKj / 0.24;
		return (int) Math.round(metabolicKj / KJ_PER_KCAL);
	}

	public static int movingTime(List<ParsedActivity.Sample> samples) {
		if (samples.isEmpty()) {
			return 0;
		}
		return samples.get(samples.size() - 1).t() - samples.get(0).t() + 1;
	}

	/** Prefers the stream's own cumulative distance; falls back to summing lap distances when the file has none. */
	public static double totalDistanceKm(List<ParsedActivity.Sample> samples, List<ParsedActivity.LapSummary> laps) {
		for (int i = samples.size() - 1; i >= 0; i--) {
			Double d = samples.get(i).distanceKm();
			if (d != null) {
				return round3(d);
			}
		}
		if (!laps.isEmpty()) {
			double sum = 0;
			for (ParsedActivity.LapSummary lap : laps) {
				sum += lap.distanceKm();
			}
			return round3(sum);
		}
		return 0.0;
	}

	/** Trailing moving average over {@link #ELEVATION_SMOOTHING_WINDOW} samples. Raw
	 * per-sample altitude carries a couple of meters of sensor noise on every reading, and
	 * summing every single positive delta unsmoothed (as this used to) inflates total
	 * ascent/descent well past the real course profile: verified against a real activity
	 * (Leeds Marathon, a course with a documented ~400m elevation gain) where the
	 * unsmoothed sum came out at 515m and a 30s average brought it to 414m. This is
	 * standard practice - every GPS/barometric platform smooths before computing elevation
	 * gain for exactly this reason. 30s (rather than a longer window) since barometric
	 * altimeters, common on GPS watches that also record power/HR, are meaningfully less
	 * noisy than GPS-derived elevation - short enough to still catch real short climbs,
	 * long enough to filter sensor noise. Revisit this constant if activities recorded
	 * from GPS-only altitude sources turn out to need more smoothing than this. */
	private static List<Double> smoothedAltitudes(List<Double> altitudes) {
		List<Double> smoothed = new ArrayList<>(altitudes.size());
		Deque<Double> window = new ArrayDeque<>();
		double total = 0;
		for (double altitude : altitudes) {
			window.addLast(altitude);
			total += altitude;
			if (window.size() > ELEVATION_SMOOTHING_WINDOW) {
				total -= window.removeFirst();
			}
			smoothed.add(total / window.size());
		}
		return smoothed;
	}

	private static List<Double> nonNullAltitudes(List<Double> altitudes) {
		List<Double> nonNull = new ArrayList<>(altitudes.size());
		for (Double altitude : altitudes) {
			if (altitude != null) {
				nonNull.add(altitude);
			}
		}
		return nonNull;
	}

	private static List<Double> nonNullAltitudesFromSamples(List<ParsedActivity.Sample> samples) {
		List<Double> altitudes = new ArrayList<>(samples.size());
		for (ParsedActivity.Sample sample : samples) {
			altitudes.add(sample.altitude());
		}
		return nonNullAltitudes(altitudes);
	}

	/** Sum of positive smoothed-altitude deltas between consecutive readings; {@code null} if fewer than two altitude samples exist. */
	public static Integer totalAscent(List<ParsedActivity.Sample> samples) {
		return totalAscentFromAltitudes(nonNullAltitudesFromSamples(samples));
	}

	/** Sum of negative smoothed-altitude deltas between consecutive readings; {@code null} if fewer than two altitude samples exist. */
	public static Integer totalDescent(List<ParsedActivity.Sample> samples) {
		return totalDescentFromAltitudes(nonNullAltitudesFromSamples(samples));
	}

	/** Same as {@link #totalAscent}, for callers (e.g. the stats-backfill path, which reads
	 * back stored Record rows rather than freshly-parsed Samples) that already have a plain
	 * altitude list. Nulls are filtered out first, same as the Sample-based overload. */
	public static Integer totalAscentFromAltitudes(List<Double> altitudes) {
		List<Double> present = nonNullAltitudes(altitudes);
		if (present.size() < 2) {
			return null;
		}
		List<Double> smoothed = smoothedAltitudes(present);
		double gain = 0;
		for (int i = 1; i < smoothed.size(); i++) {
			double delta = smoothed.get(i) - smoothed.get(i - 1);
			if (delta > 0) {
				gain += delta;
			}
		}
		return (int) Math.round(gain);
	}

	/** Same as {@link #totalDescent}, for callers that already have a plain altitude list -
	 * see {@link #totalAscentFromAltitudes}. */
	public static Integer totalDescentFromAltitudes(List<Double> altitudes) {
		List<Double> present = nonNullAltitudes(altitudes);
		if (present.size() < 2) {
			return null;
		}
		List<Double> smoothed = smoothedAltitudes(present);
		double loss = 0;
		for (int i = 1; i < smoothed.size(); i++) {
			double delta = smoothed.get(i - 1) - smoothed.get(i);
			if (delta > 0) {
				loss += delta;
			}
		}
		return (int) Math.round(loss);
	}

	private static double round3(double v) {
		return Math.round(v * 1000) / 1000.0;
	}

	private UploadCalculations() {
	}
}
