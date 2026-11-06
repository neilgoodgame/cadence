package com.cadence.api.activities.calc;

import static org.assertj.core.api.Assertions.assertThat;

import com.cadence.api.activities.calc.ActivityMergeAlignmentCalculator.AlignmentResult;
import com.cadence.api.activities.calc.ActivityMergeAlignmentCalculator.Sample;
import com.cadence.api.activities.calc.ActivityMergeAlignmentCalculator.Signal;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.IntUnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * Issue #501 (phase 1): mirrors backend's activities/tests/test_merge_alignment.py - pure
 * computation, no Spring context, so no {@code IntegrationTest} base class.
 */
class ActivityMergeAlignmentCalculatorTest {

	// A deterministic-but-non-periodic signal keyed by t. A periodic signal (e.g. a sine wave)
	// is the wrong fixture here: any offset differing from the true one by a multiple of the
	// period produces an equally perfect correlation ("aliasing"), so the test would pass even
	// if the search picked a plausible-looking wrong answer. This has no repeating structure, so
	// only the true offset lines a shifted copy up with itself.
	private static int profile(int t, int seed, double base, double amplitude) {
		Random rng = new Random((long) seed * 100_000 + t);
		return (int) Math.round(base + amplitude * (rng.nextDouble() * 2 - 1));
	}

	private static int profile(int t) {
		return profile(t, 0, 80.0, 10.0);
	}

	private static List<Sample> records(int durationS, IntUnaryOperator cadenceFn, IntUnaryOperator powerFn) {
		List<Sample> records = new ArrayList<>();
		for (int t = 0; t < durationS; t++) {
			records.add(new Sample(t, cadenceFn == null ? null : cadenceFn.applyAsInt(t),
					powerFn == null ? null : powerFn.applyAsInt(t)));
		}
		return records;
	}

	/** Builds a "secondary" activity's own rows, where its local time t represents the same
	 * real-world moment as the primary's local time (t + realOffset) - i.e. the secondary
	 * started realOffset seconds after the primary. */
	private static List<Sample> shiftedRecords(int durationS, int realOffset, IntUnaryOperator cadenceFn, IntUnaryOperator powerFn) {
		List<Sample> records = new ArrayList<>();
		for (int t = 0; t < durationS; t++) {
			records.add(new Sample(t, cadenceFn == null ? null : cadenceFn.applyAsInt(t + realOffset),
					powerFn == null ? null : powerFn.applyAsInt(t + realOffset)));
		}
		return records;
	}

	@Test
	void recoversAKnownOffsetFromCorrelatedCadence() {
		int realOffset = 45;
		List<Sample> primary = records(300, ActivityMergeAlignmentCalculatorTest::profile, null);
		List<Sample> secondary = shiftedRecords(300, realOffset, ActivityMergeAlignmentCalculatorTest::profile, null);

		AlignmentResult result = ActivityMergeAlignmentCalculator.align(primary, secondary, realOffset);

		assertThat(result.signal()).isEqualTo(Signal.CADENCE);
		assertThat(Math.abs(result.offsetSeconds() - realOffset)).isLessThanOrEqualTo(1);
		assertThat(result.score()).isGreaterThan(0.9);
	}

	@Test
	void refinesASlightlyWrongInitialGuessToTheTrueOffset() {
		// A caller's initial guess (start-date delta) can be off by a few seconds of clock skew -
		// the +/-120 s search must still land on the real offset, not just echo back a bad guess.
		int realOffset = 45;
		List<Sample> primary = records(300, ActivityMergeAlignmentCalculatorTest::profile, null);
		List<Sample> secondary = shiftedRecords(300, realOffset, ActivityMergeAlignmentCalculatorTest::profile, null);

		AlignmentResult result = ActivityMergeAlignmentCalculator.align(primary, secondary, realOffset - 15);

		assertThat(Math.abs(result.offsetSeconds() - realOffset)).isLessThanOrEqualTo(1);
		assertThat(result.score()).isGreaterThan(0.9);
	}

	@Test
	void fallsBackToPowerWhenNeitherSideHasCadence() {
		int realOffset = 20;
		IntUnaryOperator powerFn = t -> profile(t, 1, 200.0, 50.0);
		List<Sample> primary = records(300, null, powerFn);
		List<Sample> secondary = shiftedRecords(300, realOffset, null, powerFn);

		AlignmentResult result = ActivityMergeAlignmentCalculator.align(primary, secondary, realOffset);

		assertThat(result.signal()).isEqualTo(Signal.POWER);
		assertThat(Math.abs(result.offsetSeconds() - realOffset)).isLessThanOrEqualTo(1);
		assertThat(result.score()).isGreaterThan(0.9);
	}

	@Test
	void prefersCadenceOverPowerWhenBothArePresent() {
		int realOffset = 10;
		Random rng = new Random(7);
		// Power is present on both sides but deliberately uncorrelated - only cadence should
		// drive the result if cadence really is tried first.
		List<Sample> primary = new ArrayList<>();
		List<Sample> secondary = new ArrayList<>();
		for (int t = 0; t < 300; t++) {
			primary.add(new Sample(t, profile(t, 2, 80.0, 10.0), 100 + rng.nextInt(200)));
			secondary.add(new Sample(t, profile(t + realOffset, 2, 80.0, 10.0), 100 + rng.nextInt(200)));
		}

		AlignmentResult result = ActivityMergeAlignmentCalculator.align(primary, secondary, realOffset);

		assertThat(result.signal()).isEqualTo(Signal.CADENCE);
		assertThat(Math.abs(result.offsetSeconds() - realOffset)).isLessThanOrEqualTo(1);
		assertThat(result.score()).isGreaterThan(0.9);
	}

	@Test
	void lowScoreForUncorrelatedCadence() {
		Random rngA = new Random(42);
		Random rngB = new Random(99);
		List<Sample> primary = records(300, t -> 60 + rngA.nextInt(41), null);
		List<Sample> secondary = records(300, t -> 60 + rngB.nextInt(41), null);

		AlignmentResult result = ActivityMergeAlignmentCalculator.align(primary, secondary, 0);

		assertThat(result.signal()).isEqualTo(Signal.CADENCE);
		assertThat(result.score()).isLessThan(0.3);
	}

	@Test
	void returnsNoneSignalAndZeroScoreWhenTheresNoDataAtAll() {
		AlignmentResult result = ActivityMergeAlignmentCalculator.align(List.of(), List.of(), 30);

		assertThat(result.signal()).isEqualTo(Signal.NONE);
		assertThat(result.score()).isEqualTo(0.0);
		assertThat(result.overlapSeconds()).isEqualTo(0);
		assertThat(result.offsetSeconds()).isEqualTo(30);
	}

	@Test
	void insufficientOverlapFallsBackToTheInitialOffset() {
		// Only 10 s of overlap at any candidate offset - below MIN_OVERLAP_SAMPLES (30), so no
		// candidate is scorable at all.
		List<Sample> primary = records(10, ActivityMergeAlignmentCalculatorTest::profile, null);
		List<Sample> secondary = shiftedRecords(10, 0, ActivityMergeAlignmentCalculatorTest::profile, null);

		AlignmentResult result = ActivityMergeAlignmentCalculator.align(primary, secondary, 0);

		assertThat(result.signal()).isEqualTo(Signal.NONE);
		assertThat(result.score()).isEqualTo(0.0);
		assertThat(result.offsetSeconds()).isEqualTo(0);
	}
}
