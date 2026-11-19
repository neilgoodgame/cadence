package com.cadence.api.activities.calc;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Issue #541: tells a real power-meter dropout (a sensor/connection glitch that reads 0 W for a
 * few seconds while the rider keeps pedalling) apart from genuine coasting (0 W because the
 * rider really did stop pedalling), using corroborating evidence from a second stream on the
 * same timeline when one's available, or cadence as a single-stream fallback. Pure computation
 * only - mirrors backend's activities/dropout_detection.py - keep the two in sync when changing
 * either.
 *
 * <p>A related but blunter precedent already exists in {@code WorkoutMatchScanService} ({@code
 * power == 0} treated unconditionally as a dropout) - safe there only because that module never
 * has a genuine 0-effort target to confuse it with. This class can't make that assumption, so it
 * requires corroborating evidence and a bounded run length instead.
 */
public final class DropoutDetector {

	// Internal defaults, not request parameters - the issue frames these as defaults, not
	// athlete-configurable settings, so there's no API surface for them yet.
	private static final int MAX_RUN_SECONDS = 5;
	private static final int REFERENCE_MIN_WATTS = 100;

	private DropoutDetector() {
	}

	public record DropoutRun(int startT, int length) {
	}

	public record DropoutResult(Set<Integer> dropoutTs, List<DropoutRun> runs) {
	}

	/** Finds runs of consecutive seconds where {@code primary} reads a literal 0 and confirms
	 * each one as a dropout (not real data) only when it's short (&lt;= 5 s - a longer zero
	 * stretch is more likely a real stop) and there's evidence it happened mid-effort:
	 * <ul>
	 * <li>{@code reference} (another stream on the *same* timeline as {@code primary}, e.g. the
	 * other side of a merge/attach/comparison) reads &gt; 100 W at every second of the run, or
	 * <li>with no {@code reference} available, {@code cadence} (on {@code primary}'s own
	 * timeline) reads &gt; 0 throughout the run *and* the seconds immediately bracketing it in
	 * {@code primary} itself read &gt; 100 W - pedalling hard right before and after a gap is the
	 * single-stream signature of a glitch rather than a real stop.
	 * </ul>
	 * Returns the confirmed dropout seconds as a flat set (for O(1) membership checks by
	 * callers) and the run list (for reporting). Neither input map needs every second covered -
	 * a missing key is treated as "no data there", distinct from a real 0. */
	public static DropoutResult detectDropouts(Map<Integer, Integer> primary, Map<Integer, Integer> reference,
			Map<Integer, Integer> cadence) {
		TreeSet<Integer> zeroTs = new TreeSet<>();
		for (Map.Entry<Integer, Integer> entry : primary.entrySet()) {
			if (entry.getValue() != null && entry.getValue() == 0) {
				zeroTs.add(entry.getKey());
			}
		}

		List<List<Integer>> runs = new ArrayList<>();
		for (int t : zeroTs) {
			if (!runs.isEmpty() && runs.get(runs.size() - 1).get(runs.get(runs.size() - 1).size() - 1) == t - 1) {
				runs.get(runs.size() - 1).add(t);
			}
			else {
				List<Integer> run = new ArrayList<>();
				run.add(t);
				runs.add(run);
			}
		}

		Set<Integer> dropoutTs = new HashSet<>();
		List<DropoutRun> dropoutRuns = new ArrayList<>();
		for (List<Integer> run : runs) {
			if (run.size() > MAX_RUN_SECONDS) {
				continue;
			}
			boolean confirmed;
			if (reference != null) {
				confirmed = run.stream().allMatch(t -> orZero(reference.get(t)) > REFERENCE_MIN_WATTS);
			}
			else if (cadence != null) {
				boolean pedalling = run.stream().allMatch(t -> orZero(cadence.get(t)) > 0);
				Integer before = primary.get(run.get(0) - 1);
				Integer after = primary.get(run.get(run.size() - 1) + 1);
				confirmed = pedalling && before != null && before > REFERENCE_MIN_WATTS && after != null
						&& after > REFERENCE_MIN_WATTS;
			}
			else {
				confirmed = false;
			}
			if (confirmed) {
				dropoutTs.addAll(run);
				dropoutRuns.add(new DropoutRun(run.get(0), run.size()));
			}
		}
		return new DropoutResult(dropoutTs, dropoutRuns);
	}

	private static int orZero(Integer value) {
		return value == null ? 0 : value;
	}
}
