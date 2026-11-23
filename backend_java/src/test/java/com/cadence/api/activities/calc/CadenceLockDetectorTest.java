package com.cadence.api.activities.calc;

import static org.assertj.core.api.Assertions.assertThat;

import com.cadence.api.activities.calc.CadenceLockDetector.LockRun;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Issue #540 (phase 6): mirrors backend's activities/tests/test_cadence_lock_detection.py - pure
 * computation, no Spring context, so no {@code IntegrationTest} base class.
 *
 * <p>All scenarios use a 60-second series (t=0..59) with values alternating by parity so Pearson
 * r is exactly computable by hand (a perfect two-value alternation in lockstep is r=1.0; against
 * a constant series it's r=0.0, since the formula's denominator is zero for a no-variance
 * series).
 */
class CadenceLockDetectorTest {

	private static Map<Integer, Integer> alternating(int n, int evenValue, int oddValue) {
		Map<Integer, Integer> series = new HashMap<>();
		for (int t = 0; t < n; t++) {
			series.put(t, t % 2 == 0 ? evenValue : oddValue);
		}
		return series;
	}

	private static Map<Integer, Integer> constant(int n, int value) {
		Map<Integer, Integer> series = new HashMap<>();
		for (int t = 0; t < n; t++) {
			series.put(t, value);
		}
		return series;
	}

	@Test
	void aLockedWindowConfirmedByAnUncorrelatedReferenceIsFlagged() {
		Map<Integer, Integer> cadence = alternating(60, 90, 70); // mean 80, std 10, cv 0.125
		Map<Integer, Integer> hr = alternating(60, 152, 148); // same parity -> perfectly correlated
		Map<Integer, Integer> referenceHr = constant(60, 150); // flat throughout -> r=0 against hr

		List<LockRun> runs = CadenceLockDetector.detectCadenceLock(hr, cadence, referenceHr);

		assertThat(runs).containsExactly(new LockRun(0, 59, 1.0, 0.0));
	}

	@Test
	void withoutAReferenceFlagsOnCadenceCorrelationAlone() {
		Map<Integer, Integer> cadence = alternating(60, 90, 70);
		Map<Integer, Integer> hr = alternating(60, 152, 148);

		List<LockRun> runs = CadenceLockDetector.detectCadenceLock(hr, cadence, null);

		assertThat(runs).containsExactly(new LockRun(0, 59, 1.0, null));
	}

	/** The actual lock signature is HR tracking cadence *better* than it tracks the reference -
	 * here the reference matches hr exactly, so there's no such gap and nothing is flagged. */
	@Test
	void aReferenceThatTracksHrAsWellAsCadenceDoesIsNotConfirmed() {
		Map<Integer, Integer> cadence = alternating(60, 90, 70);
		Map<Integer, Integer> hr = alternating(60, 152, 148);
		Map<Integer, Integer> referenceHr = new HashMap<>(hr); // tracks hr perfectly too - r=1.0

		List<LockRun> runs = CadenceLockDetector.detectCadenceLock(hr, cadence, referenceHr);

		assertThat(runs).isEmpty();
	}

	/** cadence varies by only +/-0.5 around 80 (cv ~0.0062, below MIN_CADENCE_CV) while hr
	 * happens to alternate in lockstep (r=1.0 if computed naively) - the near-constant-cadence
	 * case the variance guard exists for: real steady-state running, not a lock. */
	@Test
	void aSteadyStateWindowIsNotFlaggedDespiteHighSpuriousCorrelation() {
		Map<Integer, Integer> cadence = alternating(60, 81, 80);
		Map<Integer, Integer> hr = alternating(60, 151, 150);

		List<LockRun> runs = CadenceLockDetector.detectCadenceLock(hr, cadence, null);

		assertThat(runs).isEmpty();
	}

	@Test
	void tooLittleDataForASingleWindowConfirmsNothing() {
		Map<Integer, Integer> cadence = alternating(20, 90, 70); // below MIN_WINDOW_SAMPLES
		Map<Integer, Integer> hr = alternating(20, 152, 148);

		List<LockRun> runs = CadenceLockDetector.detectCadenceLock(hr, cadence, null);

		assertThat(runs).isEmpty();
	}

	@Test
	void noOverlapBetweenHrAndCadenceConfirmsNothing() {
		Map<Integer, Integer> hr = alternating(60, 152, 148);
		Map<Integer, Integer> cadence = new HashMap<>();

		List<LockRun> runs = CadenceLockDetector.detectCadenceLock(hr, cadence, null);

		assertThat(runs).isEmpty();
	}
}
