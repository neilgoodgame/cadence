package com.cadence.api.activities.calc;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Issue #540 (phase 6): tells a real contact-loss glitch (a sensor reading a frozen/garbage
 * value or a physiologically-impossible jump for a few seconds, e.g. a wrist HR strap losing
 * skin contact) apart from a genuine physiological event, using corroborating evidence from a
 * reference stream on the same timeline (e.g. a chest strap against a wrist HR primary). Pure
 * computation only - mirrors backend's activities/implausible_jump_detection.py - keep the two
 * in sync when changing either.
 *
 * <p>Doesn't generalize {@link DropoutDetector}'s literal-0 power case: "coasting" (a real,
 * large, instant drop to 0 W) is normal for power but never physiologically normal for
 * heartrate, so this class is triggered by an implausible second-to-second *jump* instead. field
 * ="power" is deliberately out of scope here - it keeps going through {@link DropoutDetector}
 * unchanged.
 *
 * <p>Same reference-corroboration interface as {@link DropoutDetector#detectDropouts} but
 * single-tier, not two-tier: unlike {@code detectDropouts} there is no single-stream (cadence)
 * fallback branch here at all - confirmation requires a reference stream, full stop.
 */
public final class ImplausibleJumpDetector {

	// Internal defaults, not request parameters - same status as DropoutDetector's own
	// MAX_RUN_SECONDS/REFERENCE_MIN_WATTS.
	private static final Map<String, Integer> MAX_PHYSIOLOGICAL_DELTA = Map.of("heartrate", 25, "cadence", 40);
	// A longer implausible stretch is a real (if unusual) physiological event, not a sensor
	// glitch - same reasoning as DropoutDetector's own 5s cap, loosened slightly since an HR
	// dropout-and-recovery can run a bit longer than a power zero-glitch. EXCEPTION, heartrate
	// only: when the run's held level (median of its values) exceeds MAX_PLAUSIBLE_HR, the
	// length cap does not apply - a sustained implausible HR is never physiological (unlike
	// power, where a sustained high reading is real effort), so a long freeze at an implausible
	// level - the canonical TICKR failure - must not be dismissed as "unusual physiology".
	// Reference confirmation still applies. Cadence keeps the plain cap (symmetry-only field,
	// per this class's v1 scope note).
	private static final int MAX_RUN_SECONDS = 10;
	// bpm - above any documented human max; internal default like the others.
	private static final int MAX_PLAUSIBLE_HR = 215;

	private ImplausibleJumpDetector() {
	}

	public record ImplausibleRun(int startT, int length, double peakDelta) {
	}

	public record ImplausibleJumpResult(Set<Integer> confirmedTs, List<ImplausibleRun> runs) {
	}

	private static double median(List<Integer> values) {
		List<Integer> ordered = new ArrayList<>(values);
		ordered.sort(null);
		int n = ordered.size();
		int mid = n / 2;
		return n % 2 == 1 ? ordered.get(mid) : (ordered.get(mid - 1) + ordered.get(mid)) / 2.0;
	}

	/** Seconds t (with t-1 also present) where the jump from t-1 to t exceeds maxDelta - the
	 * "entry"/"exit" jumps a glitch's spike shape is bounded by. Only computed across genuinely
	 * adjacent seconds (t-1, t both present and one second apart) - a data gap can't be read as
	 * a per-second rate, so it never contributes a boundary. */
	private static List<Integer> boundaryTs(Map<Integer, Integer> primary, int maxDelta) {
		TreeSet<Integer> ts = new TreeSet<>();
		for (Map.Entry<Integer, Integer> entry : primary.entrySet()) {
			if (entry.getValue() != null) {
				ts.add(entry.getKey());
			}
		}
		List<Integer> boundaries = new ArrayList<>();
		Integer previous = null;
		for (int t : ts) {
			if (previous != null && t == previous + 1 && Math.abs(primary.get(t) - primary.get(previous)) > maxDelta) {
				boundaries.add(t);
			}
			previous = t;
		}
		return boundaries;
	}

	/** A candidate run is the held span between two consecutive boundary jumps - "jump away,
	 * hold at an implausible level, jump back", not a smooth ramp. A boundary jump with no
	 * following boundary (the glitch never resolves before the data ends) isn't reported - same
	 * "no trailing bracket, no confirmation" stance DropoutDetector's single-stream fallback
	 * already takes at the very start of a recording. */
	private static List<List<Integer>> findRuns(Map<Integer, Integer> primary, int maxDelta) {
		List<Integer> boundaries = boundaryTs(primary, maxDelta);
		List<List<Integer>> runs = new ArrayList<>();
		for (int i = 0; i < boundaries.size() - 1; i++) {
			List<Integer> run = new ArrayList<>();
			for (int t = boundaries.get(i); t < boundaries.get(i + 1); t++) {
				run.add(t);
			}
			runs.add(run);
		}
		return runs;
	}

	/** Finds runs of consecutive seconds where {@code primary}'s second-to-second change exceeds
	 * {@code MAX_PHYSIOLOGICAL_DELTA[field]} on entry and/or exit (a glitch looks like a spike:
	 * jump away, hold at an implausible level, jump back - not a smooth ramp), confirmed as a
	 * dropout only when short (&lt;= {@code MAX_RUN_SECONDS}) - except heartrate runs held above
	 * {@code MAX_PLAUSIBLE_HR}, confirmed at any length (the freeze case) - and {@code reference}
	 * (same-timeline corroborating stream) stays smooth and plausible throughout the run - no
	 * reference, no confirmation (unlike {@link DropoutDetector}'s cadence fallback, there's no
	 * reliable single-stream signature for an HR glitch, so this returns nothing confirmed
	 * without a reference rather than guessing). {@code field} must be "heartrate" or "cadence" -
	 * "power" keeps going through {@link DropoutDetector}. */
	public static ImplausibleJumpResult detectImplausibleJumps(Map<Integer, Integer> primary, String field,
			Map<Integer, Integer> reference) {
		Integer maxDeltaBoxed = MAX_PHYSIOLOGICAL_DELTA.get(field);
		if (maxDeltaBoxed == null) {
			throw new IllegalArgumentException("detectImplausibleJumps does not support field '" + field + "'.");
		}
		int maxDelta = maxDeltaBoxed;

		Set<Integer> confirmedTs = new HashSet<>();
		List<ImplausibleRun> confirmedRuns = new ArrayList<>();
		for (List<Integer> run : findRuns(primary, maxDelta)) {
			List<Integer> heldValues = run.stream().map(primary::get).toList();
			double heldLevel = median(heldValues);
			boolean longFreezeExempt = field.equals("heartrate") && heldLevel > MAX_PLAUSIBLE_HR;
			if (run.size() > MAX_RUN_SECONDS && !longFreezeExempt) {
				continue;
			}

			if (reference == null) {
				continue;
			}
			List<Integer> referenceValues = run.stream().map(reference::get).toList();
			if (referenceValues.stream().anyMatch(java.util.Objects::isNull)) {
				continue;
			}
			boolean smooth = true;
			for (int i = 1; i < referenceValues.size(); i++) {
				if (Math.abs(referenceValues.get(i) - referenceValues.get(i - 1)) > maxDelta) {
					smooth = false;
					break;
				}
			}
			boolean plausible = !field.equals("heartrate") || referenceValues.stream().allMatch(v -> v <= MAX_PLAUSIBLE_HR);
			if (!smooth || !plausible) {
				continue;
			}

			int entryDelta = Math.abs(primary.get(run.get(0)) - primary.get(run.get(0) - 1));
			int exitDelta = Math.abs(primary.get(run.get(run.size() - 1) + 1) - primary.get(run.get(run.size() - 1)));
			confirmedTs.addAll(run);
			confirmedRuns.add(new ImplausibleRun(run.get(0), run.size(), Math.max(entryDelta, exitDelta)));
		}
		return new ImplausibleJumpResult(confirmedTs, confirmedRuns);
	}
}
