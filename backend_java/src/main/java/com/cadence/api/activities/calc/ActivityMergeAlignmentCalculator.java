package com.cadence.api.activities.calc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Issue #501 (phase 1): computes the time offset and alignment confidence between two activities
 * suspected of being the same ride recorded twice (see #498's own duplicate detection) - the
 * foundation everything else in #501 builds on (preview, merge, automatic merging). Pure
 * computation only: no database access - callers fetch the two activities' Record streams and
 * the start-date delta, and pass them in. Mirrors backend's activities/merge_alignment.py - keep
 * the two in sync when changing either.
 */
public final class ActivityMergeAlignmentCalculator {

	// Below this many overlapping (non-null, paired) samples, a correlation score isn't
	// trustworthy enough to report for that candidate offset - the pair is skipped rather than
	// scored near-zero.
	private static final int MIN_OVERLAP_SAMPLES = 30;
	// How far past the initial guess (start-date delta) to search, in both directions - observed
	// real-world offsets for Zwift/Garmin pairs of the same ride run from a few seconds to ~55 s
	// (see issue #501), so +/-120 s comfortably covers it with margin.
	private static final int SEARCH_RADIUS_S = 120;

	private ActivityMergeAlignmentCalculator() {
	}

	/** One activity's own (t, cadence, power) row - cadence/power are null when that channel
	 * wasn't recorded at that second. */
	public record Sample(int t, Integer cadence, Integer power) {
	}

	public enum Signal { CADENCE, POWER, NONE }

	public record AlignmentResult(int offsetSeconds, double score, Signal signal, int overlapSeconds) {
	}

	/** {@code primaryRecords}/{@code secondaryRecords}: each activity's own rows, in any order -
	 * a later row for a repeated {@code t} silently wins. {@code initialOffsetSeconds} is
	 * secondary's start date minus the primary's, in whole seconds - the caller's own one-line
	 * computation, kept out of this method so it stays pure and testable without Instants.
	 *
	 * <p>Tries cadence first - the more device-independent signal (Kickr vs. pedal power read
	 * meaningfully differently for the same effort, see #499; cadence doesn't) - only falling
	 * back to power when either side has no usable cadence samples at all. */
	public static AlignmentResult align(List<Sample> primaryRecords, List<Sample> secondaryRecords, int initialOffsetSeconds) {
		Map<Integer, Integer> primaryCadence = toMap(primaryRecords, Sample::cadence);
		Map<Integer, Integer> secondaryCadence = toMap(secondaryRecords, Sample::cadence);
		if (!primaryCadence.isEmpty() && !secondaryCadence.isEmpty()) {
			BestOffset best = bestOffset(primaryCadence, secondaryCadence, initialOffsetSeconds);
			if (best.overlap() > 0) {
				return new AlignmentResult(best.offset(), best.score(), Signal.CADENCE, best.overlap());
			}
		}

		Map<Integer, Integer> primaryPower = toMap(primaryRecords, Sample::power);
		Map<Integer, Integer> secondaryPower = toMap(secondaryRecords, Sample::power);
		if (!primaryPower.isEmpty() && !secondaryPower.isEmpty()) {
			BestOffset best = bestOffset(primaryPower, secondaryPower, initialOffsetSeconds);
			if (best.overlap() > 0) {
				return new AlignmentResult(best.offset(), best.score(), Signal.POWER, best.overlap());
			}
		}

		return new AlignmentResult(initialOffsetSeconds, 0.0, Signal.NONE, 0);
	}

	private record BestOffset(int offset, double score, int overlap) {
	}

	/** Searches candidate offsets around {@code initialOffset}; for each, pairs up
	 * {@code primary[t]} with {@code secondary[t - offset]} wherever both exist, and scores the
	 * pairing by Pearson correlation - offset is how much later (in seconds) the secondary
	 * activity started than the primary, so the primary's local time t corresponds to the
	 * secondary's local time (t - offset). Falls back to {@code initialOffset}/a zero score/zero
	 * overlap if no candidate offset had enough overlap to score at all. */
	private static BestOffset bestOffset(Map<Integer, Integer> primary, Map<Integer, Integer> secondary, int initialOffset) {
		int bestOffset = initialOffset;
		double bestScore = 0.0;
		int bestOverlap = 0;
		for (int offset = initialOffset - SEARCH_RADIUS_S; offset <= initialOffset + SEARCH_RADIUS_S; offset++) {
			List<double[]> pairs = new ArrayList<>();
			for (Map.Entry<Integer, Integer> entry : primary.entrySet()) {
				Integer secondaryValue = secondary.get(entry.getKey() - offset);
				if (secondaryValue != null) {
					pairs.add(new double[] { entry.getValue(), secondaryValue });
				}
			}
			if (pairs.size() < MIN_OVERLAP_SAMPLES) {
				continue;
			}
			double score = pearson(pairs);
			if (score > bestScore || (score == bestScore && pairs.size() > bestOverlap)) {
				bestOffset = offset;
				bestScore = score;
				bestOverlap = pairs.size();
			}
		}
		return new BestOffset(bestOffset, bestScore, bestOverlap);
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

	private static Map<Integer, Integer> toMap(List<Sample> records, Function<Sample, Integer> extractor) {
		Map<Integer, Integer> map = new HashMap<>();
		for (Sample sample : records) {
			Integer value = extractor.apply(sample);
			if (value != null) {
				map.put(sample.t(), value);
			}
		}
		return map;
	}
}
