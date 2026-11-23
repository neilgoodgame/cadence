package com.cadence.api.activities.calc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cadence.api.activities.calc.ImplausibleJumpDetector.ImplausibleJumpResult;
import com.cadence.api.activities.calc.ImplausibleJumpDetector.ImplausibleRun;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Issue #540 (phase 6): mirrors backend's activities/tests/test_implausible_jump_detection.py -
 * pure computation, no Spring context, so no {@code IntegrationTest} base class.
 *
 * <p>All scenarios use before/spike/after shaped series so a perfect two-value alternation makes
 * the boundary jumps and peak deltas exactly hand-computable.
 */
class ImplausibleJumpDetectorTest {

	private static Map<Integer, Integer> constant(int n, int value, int start) {
		Map<Integer, Integer> series = new HashMap<>();
		for (int i = 0; i < n; i++) {
			series.put(start + i, value);
		}
		return series;
	}

	/** before seconds of baseline, then spikeLen seconds at spikeValue, then after seconds back
	 * at baseline - seconds 0..(before + spikeLen + after - 1). */
	private static Map<Integer, Integer> spike(int baseline, int before, int spikeValue, int spikeLen, int after) {
		Map<Integer, Integer> series = new HashMap<>();
		series.putAll(constant(before, baseline, 0));
		series.putAll(constant(spikeLen, spikeValue, before));
		series.putAll(constant(after, baseline, before + spikeLen));
		return series;
	}

	@Test
	void aShortHeartrateSpikeWithASmoothPlausibleReferenceIsConfirmed() {
		Map<Integer, Integer> primary = spike(80, 4, 180, 2, 4);
		Map<Integer, Integer> reference = constant(10, 150, 0);

		ImplausibleJumpResult result = ImplausibleJumpDetector.detectImplausibleJumps(primary, "heartrate", reference);

		assertThat(result.confirmedTs()).containsExactlyInAnyOrder(4, 5);
		assertThat(result.runs()).containsExactly(new ImplausibleRun(4, 2, 100.0));
	}

	@Test
	void cadenceSpikeAlsoConfirmsSymmetryOnly() {
		Map<Integer, Integer> primary = spike(85, 4, 180, 2, 4);
		Map<Integer, Integer> reference = constant(10, 86, 0);

		ImplausibleJumpResult result = ImplausibleJumpDetector.detectImplausibleJumps(primary, "cadence", reference);

		assertThat(result.confirmedTs()).containsExactlyInAnyOrder(4, 5);
		assertThat(result.runs()).containsExactly(new ImplausibleRun(4, 2, 95.0));
	}

	@Test
	void noReferenceConfirmsNothing() {
		Map<Integer, Integer> primary = spike(80, 4, 180, 2, 4);

		ImplausibleJumpResult result = ImplausibleJumpDetector.detectImplausibleJumps(primary, "heartrate", null);

		assertThat(result.confirmedTs()).isEmpty();
		assertThat(result.runs()).isEmpty();
	}

	/** Both streams spike together - no corroboration of a one-sided glitch, so unconfirmed, same
	 * "mutual" reasoning DropoutDetector applies to mutual near-zero. */
	@Test
	void aReferenceThatAlsoJumpsIsNotConfirmed() {
		Map<Integer, Integer> primary = spike(80, 4, 180, 2, 4);
		Map<Integer, Integer> reference = spike(150, 4, 250, 2, 4);

		ImplausibleJumpResult result = ImplausibleJumpDetector.detectImplausibleJumps(primary, "heartrate", reference);

		assertThat(result.confirmedTs()).isEmpty();
		assertThat(result.runs()).isEmpty();
	}

	@Test
	void aReferenceMissingDataInTheRunDoesNotConfirm() {
		Map<Integer, Integer> primary = spike(80, 4, 180, 2, 4);
		Map<Integer, Integer> reference = constant(10, 150, 0);
		reference.remove(5);

		ImplausibleJumpResult result = ImplausibleJumpDetector.detectImplausibleJumps(primary, "heartrate", reference);

		assertThat(result.confirmedTs()).isEmpty();
		assertThat(result.runs()).isEmpty();
	}

	/** The spike never jumps back before the data ends - no exit bracket, same "no trailing
	 * bracket, no confirmation" stance DropoutDetector's single-stream fallback takes. */
	@Test
	void aRunWithNoClosingBoundaryIsNotReported() {
		Map<Integer, Integer> primary = constant(4, 80, 0);
		primary.put(4, 180);
		primary.put(5, 180);
		Map<Integer, Integer> reference = constant(6, 150, 0);

		ImplausibleJumpResult result = ImplausibleJumpDetector.detectImplausibleJumps(primary, "heartrate", reference);

		assertThat(result.confirmedTs()).isEmpty();
		assertThat(result.runs()).isEmpty();
	}

	/** The canonical TICKR failure: a long freeze at an implausible level must not be dismissed
	 * as "unusual physiology" the way a long run normally would be. */
	@Test
	void aLongHeartrateFreezeHeldAboveMaxPlausibleHrIsConfirmedDespiteTheLengthCap() {
		Map<Integer, Integer> primary = spike(80, 4, 230, 12, 4);
		Map<Integer, Integer> reference = constant(20, 150, 0);

		ImplausibleJumpResult result = ImplausibleJumpDetector.detectImplausibleJumps(primary, "heartrate", reference);

		assertThat(result.confirmedTs()).containsExactlyInAnyOrder(4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15);
		assertThat(result.runs()).containsExactly(new ImplausibleRun(4, 12, 150.0));
	}

	/** Same shape and length as the freeze test above, but the held level (180 bpm) is itself
	 * plausible - a long run at a plausible level is unusual physiology, not a sensor glitch,
	 * same reasoning DropoutDetector applies to its own longer-zero-run rejection. */
	@Test
	void theSameLongRunAtAPlausibleHeldLevelIsNotConfirmed() {
		Map<Integer, Integer> primary = spike(80, 4, 180, 12, 4);
		Map<Integer, Integer> reference = constant(20, 150, 0);

		ImplausibleJumpResult result = ImplausibleJumpDetector.detectImplausibleJumps(primary, "heartrate", reference);

		assertThat(result.confirmedTs()).isEmpty();
		assertThat(result.runs()).isEmpty();
	}

	/** The MAX_PLAUSIBLE_HR exemption is heartrate-only - cadence keeps the plain length cap
	 * regardless of how high the held level is. */
	@Test
	void aLongCadenceRunGetsNoFreezeExemption() {
		Map<Integer, Integer> primary = spike(85, 4, 230, 12, 4);
		Map<Integer, Integer> reference = constant(20, 86, 0);

		ImplausibleJumpResult result = ImplausibleJumpDetector.detectImplausibleJumps(primary, "cadence", reference);

		assertThat(result.confirmedTs()).isEmpty();
		assertThat(result.runs()).isEmpty();
	}

	@Test
	void anUnsupportedFieldThrows() {
		Map<Integer, Integer> primary = Map.of(0, 0, 1, 300);

		assertThatThrownBy(() -> ImplausibleJumpDetector.detectImplausibleJumps(primary, "power", null))
				.isInstanceOf(IllegalArgumentException.class);
	}

	/** Code review finding: a run built as range(entryBoundary, exitBoundary) assumed every
	 * second in between was present. A single missing sample inside the held span (one that was
	 * never itself a boundary, since it has no neighbor to be adjacent to) used to NPE in
	 * median() - the gap must be skipped, not treated as a real held value. */
	@Test
	void aMissingSampleInsideARunDoesNotCrash() {
		Map<Integer, Integer> primary = new HashMap<>(constant(4, 80, 0));
		primary.put(4, 180);
		primary.put(5, 180);
		// t=6 is missing entirely - a genuine gap inside the held span.
		primary.put(7, 180);
		primary.putAll(constant(4, 80, 8));
		Map<Integer, Integer> reference = constant(12, 150, 0);

		ImplausibleJumpResult result = ImplausibleJumpDetector.detectImplausibleJumps(primary, "heartrate", reference);

		assertThat(result.confirmedTs()).containsExactlyInAnyOrder(4, 5, 7);
		assertThat(result.runs()).containsExactly(new ImplausibleRun(4, 3, 100.0));
	}
}
