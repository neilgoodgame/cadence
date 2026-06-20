package com.cadence.api.uploads;

import com.cadence.api.uploads.parsing.ParsedActivity;
import java.util.List;

/** Pure derivations from a parsed file's samples/laps that don't depend on the athlete's profile. */
public final class UploadCalculations {

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

	/** Sum of positive altitude deltas between consecutive readings; {@code null} if fewer than two altitude samples exist. */
	public static Integer totalAscent(List<ParsedActivity.Sample> samples) {
		Double previous = null;
		double gain = 0;
		int count = 0;
		for (ParsedActivity.Sample sample : samples) {
			Double altitude = sample.altitude();
			if (altitude == null) {
				continue;
			}
			count++;
			if (previous != null && altitude > previous) {
				gain += altitude - previous;
			}
			previous = altitude;
		}
		return count < 2 ? null : (int) Math.round(gain);
	}

	private static double round3(double v) {
		return Math.round(v * 1000) / 1000.0;
	}

	private UploadCalculations() {
	}
}
