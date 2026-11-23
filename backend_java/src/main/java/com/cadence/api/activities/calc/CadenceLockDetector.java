package com.cadence.api.activities.calc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Issue #540 (phase 6): detects a wrist HR stream correlating with cadence instead of a
 * reference strap/pedal stream ("cadence lock" - a known wrist-optical HR failure mode: the
 * sensor loses a real pulse signal and the algorithm it runs falls back to arm-swing/cadence
 * rhythm instead). Genuinely new - no {@link DropoutDetector}/{@link ImplausibleJumpDetector}
 * precedent to mirror structurally; the thresholds below are this class's own proposal,
 * untested against real TICKR data (flagged in the PR, see issue #540's own design doc). Mirrors
 * backend's activities/cadence_lock_detection.py - keep the two in sync when changing either.
 *
 * <p>Ship behind the same "always computed, never silently trusted" posture {@link
 * DropoutDetector} already established (report confidence/evidence, let the athlete/UI judge) -
 * this class never auto-patches anything itself; it only ever reports what it found.
 */
public final class CadenceLockDetector {

	private static final int WINDOW_SECONDS = 60;
	private static final int STEP_SECONDS = 10; // sliding window hop
	// Pearson r between HR and cadence within a window.
	private static final double LOCK_CORRELATION_THRESHOLD = 0.85;
	// Reuses StreamComparisonCalculator.MIN_OVERLAP_SAMPLES's floor, not a new value.
	private static final int MIN_WINDOW_SAMPLES = StreamComparisonCalculator.MIN_OVERLAP_SAMPLES;
	// Coefficient of variation (std/mean) the window's cadence series must reach before its
	// Pearson r is trusted - on a near-constant cadence (steady-state running, the common case)
	// r between two low-variance series is noise-dominated and can spuriously cross the lock
	// threshold, while the actual lock signature requires HR to track cadence *variation*,
	// which needs variation. Untested guess, tuned alongside LOCK_CORRELATION_THRESHOLD.
	private static final double MIN_CADENCE_CV = 0.02;

	private CadenceLockDetector() {
	}

	/** note: {@link DropoutDetector.DropoutRun}/{@link ImplausibleJumpDetector.ImplausibleRun}
	 * use startT+length, not startT+endT - callers assembling a combined streams/flags response
	 * normalize both shapes to one [startT, endT] inclusive pair. */
	public record LockRun(int startT, int endT, double cadenceCorrelation, Double referenceCorrelation) {
	}

	private record Window(int start, int end) {
	}

	private record Flagged(int startT, int endT, double cadenceCorrelation, Double referenceCorrelation) {
	}

	private static double coefficientOfVariation(List<Integer> values) {
		int n = values.size();
		double mean = values.stream().mapToInt(Integer::intValue).average().orElse(0);
		if (mean == 0) {
			return 0.0;
		}
		double variance = values.stream().mapToDouble(v -> (v - mean) * (v - mean)).sum() / n;
		return Math.sqrt(variance) / mean;
	}

	/** Half-open [windowStart, windowStart + WINDOW_SECONDS) windows hopping by STEP_SECONDS,
	 * covering [start, end]. */
	private static List<Window> windows(int start, int end) {
		List<Window> windows = new ArrayList<>();
		for (int t = start; t < end; t += STEP_SECONDS) {
			windows.add(new Window(t, t + WINDOW_SECONDS));
		}
		return windows;
	}

	private record Paired(int t, double hr, double cadence) {
	}

	private static List<Paired> paired(Map<Integer, Integer> hr, Map<Integer, Integer> cadence, int windowStart,
			int windowEnd) {
		List<Paired> pairs = new ArrayList<>();
		for (int t = windowStart; t < windowEnd; t++) {
			Integer hrValue = hr.get(t);
			Integer cadenceValue = cadence.get(t);
			if (hrValue != null && cadenceValue != null) {
				pairs.add(new Paired(t, hrValue, cadenceValue));
			}
		}
		return pairs;
	}

	/** Slides a WINDOW_SECONDS window (hop STEP_SECONDS) over {@code hrPrimary}'s overlap with
	 * {@code cadence}; windows whose cadence coefficient of variation (std/mean) is below
	 * MIN_CADENCE_CV are skipped outright - on a near-constant cadence (steady-state running, the
	 * common case) Pearson r between two low-variance series is noise-dominated and can
	 * spuriously cross the lock threshold, while the actual lock signature requires HR to track
	 * cadence *variation*, which needs variation. For each remaining window with &gt;=
	 * MIN_WINDOW_SAMPLES paired seconds, computes Pearson r between hrPrimary and cadence. Flags
	 * a window when that correlation &gt;= LOCK_CORRELATION_THRESHOLD AND, when {@code
	 * referenceHr} is available, the window's HR-vs-reference correlation is lower than its
	 * HR-vs-cadence correlation (HR is tracking the wrist's own cadence sensor better than it's
	 * tracking a presumed-truth HR source - the actual lock signature). With no {@code
	 * referenceHr}, flags on the cadence-correlation threshold alone - weaker evidence, same "no
	 * reference, no confirmation" caution as {@link ImplausibleJumpDetector}, so the response/UI
	 * must visibly distinguish reference-confirmed flags from referenceHr=null ones rather than
	 * presenting both identically. Adjacent/overlapping flagged windows are merged into one
	 * {@link LockRun}. */
	public static List<LockRun> detectCadenceLock(Map<Integer, Integer> hrPrimary, Map<Integer, Integer> cadence,
			Map<Integer, Integer> referenceHr) {
		TreeSet<Integer> overlapTs = new TreeSet<>(hrPrimary.keySet());
		overlapTs.retainAll(cadence.keySet());
		if (overlapTs.isEmpty()) {
			return List.of();
		}

		List<Flagged> flagged = new ArrayList<>();
		for (Window window : windows(overlapTs.first(), overlapTs.last() + 1)) {
			List<Paired> pairs = paired(hrPrimary, cadence, window.start(), window.end());
			if (pairs.size() < MIN_WINDOW_SAMPLES) {
				continue;
			}
			List<Integer> cadenceValues = pairs.stream().map(p -> (int) p.cadence()).toList();
			if (coefficientOfVariation(cadenceValues) < MIN_CADENCE_CV) {
				continue;
			}
			double cadenceCorrelation = pearsonOf(pairs, p -> p.hr(), p -> p.cadence());
			if (cadenceCorrelation < LOCK_CORRELATION_THRESHOLD) {
				continue;
			}

			Double referenceCorrelation = null;
			if (referenceHr != null) {
				List<double[]> referencePairs = new ArrayList<>();
				for (Paired pair : pairs) {
					Integer referenceValue = referenceHr.get(pair.t());
					if (referenceValue != null) {
						referencePairs.add(new double[] { pair.hr(), referenceValue });
					}
				}
				if (referencePairs.size() < MIN_WINDOW_SAMPLES) {
					continue;
				}
				referenceCorrelation = StreamComparisonCalculator.pearson(referencePairs);
				if (referenceCorrelation >= cadenceCorrelation) {
					continue;
				}
			}

			flagged.add(new Flagged(pairs.get(0).t(), pairs.get(pairs.size() - 1).t(), cadenceCorrelation,
					referenceCorrelation));
		}

		return mergeRuns(flagged);
	}

	private static double pearsonOf(List<Paired> pairs, java.util.function.ToDoubleFunction<Paired> x,
			java.util.function.ToDoubleFunction<Paired> y) {
		List<double[]> values = new ArrayList<>();
		for (Paired pair : pairs) {
			values.add(new double[] { x.applyAsDouble(pair), y.applyAsDouble(pair) });
		}
		return StreamComparisonCalculator.pearson(values);
	}

	/** Adjacent/overlapping flagged windows merge into one LockRun, keeping the strongest
	 * (highest) cadenceCorrelation among the merged windows as the reported value, and the
	 * referenceCorrelation that goes with it - a merged run is "reference-confirmed" overall
	 * only if every window that makes it up was (matching this class's own "no reference, no
	 * confirmation" caution: a run can't borrow confirmation from a neighboring window). */
	private static List<LockRun> mergeRuns(List<Flagged> flagged) {
		if (flagged.isEmpty()) {
			return List.of();
		}
		List<Flagged> sorted = flagged.stream().sorted(java.util.Comparator.comparingInt(Flagged::startT)).toList();
		List<LockRun> runs = new ArrayList<>();
		int currentStart = sorted.get(0).startT();
		int currentEnd = sorted.get(0).endT();
		double bestCorr = sorted.get(0).cadenceCorrelation();
		Double bestRefCorr = sorted.get(0).referenceCorrelation();
		boolean anyUnconfirmed = bestRefCorr == null;
		for (int i = 1; i < sorted.size(); i++) {
			Flagged f = sorted.get(i);
			if (f.startT() <= currentEnd) {
				currentEnd = Math.max(currentEnd, f.endT());
				anyUnconfirmed = anyUnconfirmed || f.referenceCorrelation() == null;
				if (f.cadenceCorrelation() > bestCorr) {
					bestCorr = f.cadenceCorrelation();
					bestRefCorr = f.referenceCorrelation();
				}
			}
			else {
				runs.add(new LockRun(currentStart, currentEnd, bestCorr, anyUnconfirmed ? null : bestRefCorr));
				currentStart = f.startT();
				currentEnd = f.endT();
				bestCorr = f.cadenceCorrelation();
				bestRefCorr = f.referenceCorrelation();
				anyUnconfirmed = bestRefCorr == null;
			}
		}
		runs.add(new LockRun(currentStart, currentEnd, bestCorr, anyUnconfirmed ? null : bestRefCorr));
		return runs;
	}
}
