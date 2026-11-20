package com.cadence.api.activities.calc;

import static org.assertj.core.api.Assertions.assertThat;

import com.cadence.api.activities.calc.DropoutDetector.DropoutResult;
import com.cadence.api.activities.calc.DropoutDetector.DropoutRun;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Issue #541: mirrors backend's activities/tests/test_dropout_detection.py - pure computation,
 * no Spring context, so no {@code IntegrationTest} base class.
 */
class DropoutDetectorTest {

	private static Map<Integer, Integer> constant(int n, int value) {
		Map<Integer, Integer> series = new HashMap<>();
		for (int t = 0; t < n; t++) {
			series.put(t, value);
		}
		return series;
	}

	@Test
	void aShortZeroRunWithAStrongReferenceIsAConfirmedDropout() {
		Map<Integer, Integer> primary = constant(10, 200);
		primary.put(4, 0);
		primary.put(5, 0);
		primary.put(6, 0);
		Map<Integer, Integer> reference = constant(10, 220);

		DropoutResult result = DropoutDetector.detectDropouts(primary, reference, null);

		assertThat(result.dropoutTs()).containsExactlyInAnyOrder(4, 5, 6);
		assertThat(result.runs()).containsExactly(new DropoutRun(4, 3));
	}

	@Test
	void mutualNearZeroIsNotADropout() {
		Map<Integer, Integer> primary = constant(10, 200);
		primary.put(4, 0);
		primary.put(5, 0);
		primary.put(6, 0);
		Map<Integer, Integer> reference = constant(10, 200);
		reference.put(4, 2);
		reference.put(5, 0);
		reference.put(6, 1);

		DropoutResult result = DropoutDetector.detectDropouts(primary, reference, null);

		assertThat(result.dropoutTs()).isEmpty();
		assertThat(result.runs()).isEmpty();
	}

	@Test
	void aRunLongerThanFiveSecondsIsNotADropoutEvenWithAStrongReference() {
		Map<Integer, Integer> primary = constant(12, 200);
		for (int t = 2; t <= 7; t++) {
			primary.put(t, 0); // 6 s, too long
		}
		Map<Integer, Integer> reference = constant(12, 220);

		DropoutResult result = DropoutDetector.detectDropouts(primary, reference, null);

		assertThat(result.dropoutTs()).isEmpty();
		assertThat(result.runs()).isEmpty();
	}

	@Test
	void singleStreamFallbackConfirmsWithCadenceAndStrongBrackets() {
		Map<Integer, Integer> primary = constant(10, 200);
		primary.put(4, 0);
		primary.put(5, 0);
		Map<Integer, Integer> cadence = constant(10, 85);

		DropoutResult result = DropoutDetector.detectDropouts(primary, null, cadence);

		assertThat(result.dropoutTs()).containsExactlyInAnyOrder(4, 5);
		assertThat(result.runs()).containsExactly(new DropoutRun(4, 2));
	}

	@Test
	void singleStreamFallbackRejectsARealFreewheelStop() {
		Map<Integer, Integer> primary = constant(10, 200);
		primary.put(4, 0);
		primary.put(5, 0);
		Map<Integer, Integer> cadence = constant(10, 85);
		cadence.put(4, 0);
		cadence.put(5, 0);

		DropoutResult result = DropoutDetector.detectDropouts(primary, null, cadence);

		assertThat(result.dropoutTs()).isEmpty();
		assertThat(result.runs()).isEmpty();
	}

	@Test
	void singleStreamFallbackRejectsARunAtTheVeryStartWithNoLeadingBracket() {
		Map<Integer, Integer> primary = new HashMap<>();
		primary.put(0, 0);
		primary.put(1, 0);
		primary.put(2, 200);
		primary.put(3, 200);
		primary.put(4, 200);
		Map<Integer, Integer> cadence = constant(5, 85);

		DropoutResult result = DropoutDetector.detectDropouts(primary, null, cadence);

		assertThat(result.dropoutTs()).isEmpty();
		assertThat(result.runs()).isEmpty();
	}

	@Test
	void noReferenceAndNoCadenceConfirmsNothing() {
		Map<Integer, Integer> primary = constant(10, 200);
		primary.put(4, 0);
		primary.put(5, 0);

		DropoutResult result = DropoutDetector.detectDropouts(primary, null, null);

		assertThat(result.dropoutTs()).isEmpty();
		assertThat(result.runs()).isEmpty();
	}

	@Test
	void twoSeparateRunsAreReportedIndependently() {
		Map<Integer, Integer> primary = constant(14, 200);
		primary.put(2, 0);
		primary.put(3, 0);
		primary.put(9, 0);
		Map<Integer, Integer> reference = constant(14, 220);

		DropoutResult result = DropoutDetector.detectDropouts(primary, reference, null);

		assertThat(result.dropoutTs()).containsExactlyInAnyOrder(2, 3, 9);
		assertThat(result.runs()).containsExactly(new DropoutRun(2, 2), new DropoutRun(9, 1));
	}

	@Test
	void aMissingSampleIsNotTreatedAsAZero() {
		Map<Integer, Integer> primary = new HashMap<>();
		primary.put(0, 200);
		primary.put(2, 200); // t=1 simply absent, not a recorded 0
		Map<Integer, Integer> reference = constant(3, 220);

		DropoutResult result = DropoutDetector.detectDropouts(primary, reference, null);

		assertThat(result.dropoutTs()).isEmpty();
		assertThat(result.runs()).isEmpty();
	}

	/** Code review finding: the reference stream can drop a sample too - a gap there shouldn't
	 * sink corroboration from the run's other, clearly-confirmed seconds. */
	@Test
	void oneMissingReferenceSampleInARunDoesNotRejectTheWholeRun() {
		Map<Integer, Integer> primary = constant(10, 200);
		primary.put(4, 0);
		primary.put(5, 0);
		primary.put(6, 0);
		Map<Integer, Integer> reference = constant(10, 220);
		reference.remove(5); // the reference's own simultaneous gap - neutral, not disconfirming

		DropoutResult result = DropoutDetector.detectDropouts(primary, reference, null);

		assertThat(result.dropoutTs()).containsExactlyInAnyOrder(4, 5, 6);
		assertThat(result.runs()).containsExactly(new DropoutRun(4, 3));
	}

	/** No evidence either way must not be treated as confirmation - only an explicit low/zero
	 * reference reading rejects a run (mutual coasting); a total absence of data means there was
	 * nothing to corroborate with in the first place. */
	@Test
	void aReferenceRunWithNoDataAtAllIsNotConfirmed() {
		Map<Integer, Integer> primary = constant(10, 200);
		primary.put(4, 0);
		primary.put(5, 0);
		Map<Integer, Integer> reference = new HashMap<>(); // no reference data at these seconds at all

		DropoutResult result = DropoutDetector.detectDropouts(primary, reference, null);

		assertThat(result.dropoutTs()).isEmpty();
		assertThat(result.runs()).isEmpty();
	}

	/** Code review finding: a reference map of all-null values must not be passed to
	 * detectDropouts as if it were real data - that silently defeats the cadence-only fallback,
	 * since {@code reference != null} would always be taken even with nothing to compare
	 * against. */
	@Test
	void buildReferenceReturnsNullWhenTheTargetHasNoDataAtAll() {
		Map<Integer, Integer> targetValuesByT = new HashMap<>();
		for (int t = 0; t < 10; t++) {
			targetValuesByT.put(t, null);
		}

		Map<Integer, Integer> reference = DropoutDetector.buildReference(targetValuesByT, 0, Set.of(0, 1, 2, 3, 4));

		assertThat(reference).isNull();
	}

	@Test
	void buildReferenceReturnsTheAlignedMapWhenTheTargetHasSomeData() {
		Map<Integer, Integer> targetValuesByT = constant(10, 220);

		Map<Integer, Integer> reference = DropoutDetector.buildReference(targetValuesByT, 3, Set.of(0, 1, 2, 3, 4));

		// source-local t -> target-local (t + offsetSeconds)
		assertThat(reference).containsOnly(Map.entry(0, 220), Map.entry(1, 220), Map.entry(2, 220),
				Map.entry(3, 220), Map.entry(4, 220));
	}
}
