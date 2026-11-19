package com.cadence.api.activities.calc;

import static org.assertj.core.api.Assertions.assertThat;

import com.cadence.api.activities.calc.DropoutDetector.DropoutResult;
import com.cadence.api.activities.calc.DropoutDetector.DropoutRun;
import java.util.HashMap;
import java.util.Map;
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
}
