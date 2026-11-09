package com.cadence.api.activities.calc;

import com.cadence.api.athletes.Zone;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Issue #503 (phase 4): compares two already-stored streams for the same field on the same
 * activity (e.g. a wrist-HR primary against an attached chest-strap source) and reports
 * agreement statistics. Pure computation only: no database access - callers resolve both sides
 * to a {@code t -> value} series first (see {@code ActivitySourceService#loadFieldSeries}) and
 * pass them in. Unlike {@link ActivityMergeAlignmentCalculator}, both series here already share
 * one activity's own {@code t} clock - any cross-recording offset was already resolved before
 * either stream was stored - so the lag this class searches for is residual/physiological (e.g.
 * wrist HR trailing a strap by a few seconds), not a clock mismatch, hence the much smaller
 * search radius. Mirrors backend's activities/stream_comparison.py - keep the two in sync.
 */
public final class StreamComparisonCalculator {

	// Below this many overlapping (non-null, paired) samples, none of these statistics are
	// trustworthy enough to report - same floor ActivityMergeAlignmentCalculator uses, for the
	// same reason.
	public static final int MIN_OVERLAP_SAMPLES = 30;
	// How far to search for a residual lag, in seconds, each direction - generously covers
	// known wrist-HR reporting lag (a handful of seconds) without the ActivityMergeAlignmentCalculator
	// counterpart's +/-120s, which is for two independently-clocked recordings, not two streams
	// already sharing this activity's own timeline.
	private static final int LAG_SEARCH_RADIUS_S = 30;

	private StreamComparisonCalculator() {
	}

	public record ComparisonResult(int overlapSeconds, double bias, double mae, double rmse, double pearsonR,
			double loaLower, double loaUpper, double withinTolerancePct) {
	}

	public record LagResult(int lagSeconds, double correlation, int overlapSeconds) {
	}

	public record PowerFit(double scale, double offset, double ratioOfMeans) {
	}

	public record ZoneBucketResult(String zoneName, int sampleCount, Double bias, Double mae, Double rmse) {
	}

	/** A tolerance spec for "% of paired seconds within X" - {@code absolute} (e.g. 5 bpm for
	 * heartrate) or a percentage of series A's own value (e.g. 3% for power/cadence), never
	 * both. */
	public record Tolerance(Double absolute, Double relativePct) {
		public static Tolerance absolute(double value) {
			return new Tolerance(value, null);
		}

		public static Tolerance relativePct(double pct) {
			return new Tolerance(null, pct);
		}
	}

	/** Pairs {@code seriesA}/{@code seriesB} on shared {@code t} and computes agreement
	 * statistics over the overlap. Returns {@code null} if the overlap has fewer than
	 * {@link #MIN_OVERLAP_SAMPLES} paired seconds - callers should reject the request rather than
	 * report a statistic computed on too little data. */
	public static ComparisonResult compare(Map<Integer, Integer> seriesA, Map<Integer, Integer> seriesB, Tolerance tolerance) {
		List<double[]> pairs = pair(seriesA, seriesB);
		if (pairs.size() < MIN_OVERLAP_SAMPLES) {
			return null;
		}
		int n = pairs.size();
		double sumDiff = 0;
		double sumAbsDiff = 0;
		double sumSqDiff = 0;
		int withinTolerance = 0;
		for (double[] pair : pairs) {
			double a = pair[0];
			double b = pair[1];
			double diff = b - a;
			sumDiff += diff;
			sumAbsDiff += Math.abs(diff);
			sumSqDiff += diff * diff;
			double allowed = tolerance.absolute() != null ? tolerance.absolute() : Math.abs(a) * tolerance.relativePct() / 100.0;
			if (Math.abs(diff) <= allowed) {
				withinTolerance++;
			}
		}
		double bias = sumDiff / n;
		double mae = sumAbsDiff / n;
		double rmse = Math.sqrt(sumSqDiff / n);
		double sumSqDevFromBias = 0;
		for (double[] pair : pairs) {
			double diff = pair[1] - pair[0];
			sumSqDevFromBias += (diff - bias) * (diff - bias);
		}
		double stddev = Math.sqrt(sumSqDevFromBias / n);
		double pearsonR = pearson(pairs);
		return new ComparisonResult(n, bias, mae, rmse, pearsonR, bias - 1.96 * stddev, bias + 1.96 * stddev,
				100.0 * withinTolerance / n);
	}

	/** Searches lags in {@code [-LAG_SEARCH_RADIUS_S, +LAG_SEARCH_RADIUS_S]}; for each, pairs
	 * {@code seriesA[t - lag]} with {@code seriesB[t]} wherever both exist and scores the pairing
	 * by Pearson correlation - a positive lag means B's value at time t best matches A's value
	 * from {@code lag} seconds earlier, i.e. B is trailing A by {@code lag} seconds (B reports
	 * late, e.g. wrist HR behind a chest strap). Returns {@code null} if no candidate lag had
	 * enough overlap to score. */
	public static LagResult findLag(Map<Integer, Integer> seriesA, Map<Integer, Integer> seriesB) {
		int bestLag = 0;
		double bestScore = -2.0;
		int bestOverlap = 0;
		for (int lag = -LAG_SEARCH_RADIUS_S; lag <= LAG_SEARCH_RADIUS_S; lag++) {
			List<double[]> pairs = new ArrayList<>();
			for (Map.Entry<Integer, Integer> entry : seriesB.entrySet()) {
				Integer aValue = seriesA.get(entry.getKey() - lag);
				if (aValue != null) {
					pairs.add(new double[] { aValue, entry.getValue() });
				}
			}
			if (pairs.size() < MIN_OVERLAP_SAMPLES) {
				continue;
			}
			double score = pearson(pairs);
			if (score > bestScore) {
				bestLag = lag;
				bestScore = score;
				bestOverlap = pairs.size();
			}
		}
		return bestOverlap == 0 ? null : new LagResult(bestLag, bestScore, bestOverlap);
	}

	/** Ordinary least-squares fit of {@code b = scale*a + offset} over the paired, overlapping
	 * samples, plus the simple ratio of the two series' means - power-field only, the
	 * drivetrain-loss estimate between two power meters. */
	public static PowerFit fitPowerScaleOffset(Map<Integer, Integer> seriesA, Map<Integer, Integer> seriesB) {
		List<double[]> pairs = pair(seriesA, seriesB);
		int n = pairs.size();
		double sumX = 0;
		double sumY = 0;
		double sumXY = 0;
		double sumX2 = 0;
		for (double[] pair : pairs) {
			sumX += pair[0];
			sumY += pair[1];
			sumXY += pair[0] * pair[1];
			sumX2 += pair[0] * pair[0];
		}
		double denom = n * sumX2 - sumX * sumX;
		double scale = denom == 0 ? 1.0 : (n * sumXY - sumX * sumY) / denom;
		double offset = (sumY - scale * sumX) / n;
		double meanA = sumX / n;
		double meanB = sumY / n;
		return new PowerFit(scale, offset, meanA == 0 ? 0.0 : meanB / meanA);
	}

	/** Buckets the paired overlap by series A's value as a %-of-{@code reference}, walking the
	 * zone bands top-down exactly as the frontend's {@code zones.ts}'s {@code bucketIntoZones}
	 * does, then reports per-zone bias/MAE/RMSE instead of just time-in-zone. A zone with no
	 * samples still appears, with null stats, matching {@code bucketIntoZones}'s "report nothing
	 * silently" behavior rather than omitting the bucket. */
	public static List<ZoneBucketResult> zoneBreakdown(Map<Integer, Integer> seriesA, Map<Integer, Integer> seriesB,
			List<Zone> zones, double reference) {
		List<List<double[]>> buckets = new ArrayList<>();
		for (int i = 0; i < zones.size(); i++) {
			buckets.add(new ArrayList<>());
		}
		for (double[] pair : pair(seriesA, seriesB)) {
			double pct = reference == 0 ? 0.0 : (pair[0] / reference) * 100.0;
			for (int i = zones.size() - 1; i >= 0; i--) {
				if (pct >= zones.get(i).lowPct() || i == 0) {
					buckets.get(i).add(pair);
					break;
				}
			}
		}
		List<ZoneBucketResult> results = new ArrayList<>();
		for (int i = 0; i < zones.size(); i++) {
			List<double[]> bucket = buckets.get(i);
			if (bucket.isEmpty()) {
				results.add(new ZoneBucketResult(zones.get(i).name(), 0, null, null, null));
				continue;
			}
			double sumDiff = 0;
			double sumAbsDiff = 0;
			double sumSqDiff = 0;
			for (double[] pair : bucket) {
				double diff = pair[1] - pair[0];
				sumDiff += diff;
				sumAbsDiff += Math.abs(diff);
				sumSqDiff += diff * diff;
			}
			int n = bucket.size();
			results.add(new ZoneBucketResult(zones.get(i).name(), n, sumDiff / n, sumAbsDiff / n, Math.sqrt(sumSqDiff / n)));
		}
		return results;
	}

	private static List<double[]> pair(Map<Integer, Integer> seriesA, Map<Integer, Integer> seriesB) {
		List<double[]> pairs = new ArrayList<>();
		for (Map.Entry<Integer, Integer> entry : new TreeMap<>(seriesA).entrySet()) {
			Integer bValue = seriesB.get(entry.getKey());
			if (bValue != null) {
				pairs.add(new double[] { entry.getValue(), bValue });
			}
		}
		return pairs;
	}

	private static double pearson(List<double[]> pairs) {
		int n = pairs.size();
		double sumX = 0;
		double sumY = 0;
		double sumXY = 0;
		double sumX2 = 0;
		double sumY2 = 0;
		for (double[] pair : pairs) {
			double x = pair[0];
			double y = pair[1];
			sumX += x;
			sumY += y;
			sumXY += x * y;
			sumX2 += x * x;
			sumY2 += y * y;
		}
		double denom = Math.sqrt((n * sumX2 - sumX * sumX) * (n * sumY2 - sumY * sumY));
		return denom == 0 ? 0.0 : (n * sumXY - sumX * sumY) / denom;
	}
}
