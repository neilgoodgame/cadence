package com.cadence.api.activities.calc;

import static org.assertj.core.api.Assertions.assertThat;

import com.cadence.api.common.domain.Sport;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Pure-math coverage for the Aerobic decoupling &amp; durability feature - mirrors the Python
 * backend's uploads/tests/test_decoupling_durability.py exactly (same scenarios, same expected
 * numbers, ported from the same algorithm). */
class DecouplingDurabilityCalculatorTest {

	// ---- steadyWindowStartIndex ----

	@Test
	void continuousRecordingEndsWarmupAtExactly600() {
		List<Integer> t = sequence(4000);
		assertThat(DecouplingQualificationCalculator.steadyWindowStartIndex(t, DecouplingQualificationCalculator.WARMUP_SECONDS))
				.isEqualTo(600);
	}

	@Test
	void aLongPauseContributesAtMostOneSecondToTheActiveClock() {
		List<Integer> t = new ArrayList<>(sequence(300));
		for (int i = 0; i < 400; i++) {
			t.add(100_000 + i);
		}
		assertThat(DecouplingQualificationCalculator.steadyWindowStartIndex(t, DecouplingQualificationCalculator.WARMUP_SECONDS))
				.isEqualTo(600);
	}

	@Test
	void tooShortToEverReachWarmupReturnsNull() {
		assertThat(DecouplingQualificationCalculator.steadyWindowStartIndex(sequence(500), DecouplingQualificationCalculator.WARMUP_SECONDS))
				.isNull();
	}

	@Test
	void aShorterConfiguredWarmupEndsEarlier() {
		List<Integer> t = sequence(400);
		assertThat(DecouplingQualificationCalculator.steadyWindowStartIndex(t, 300)).isEqualTo(300);
	}

	@Test
	void emptySeriesReturnsNull() {
		assertThat(DecouplingQualificationCalculator.steadyWindowStartIndex(List.of(), DecouplingQualificationCalculator.WARMUP_SECONDS)).isNull();
	}

	// ---- checkQualification ----

	// Wraps checkQualification with the design spec's own default thresholds (now
	// athlete-configurable - see User.decouplingViLimitBike/Run/decouplingIfLimit/
	// decouplingMinSteadyMinutes), so these pure-math tests don't need to repeat them at every
	// call site. A test that cares about a non-default threshold overrides it explicitly.
	private static DecouplingQualificationCalculator.Result check(Sport sport, List<Integer> power, List<Integer> hr, Double threshold) {
		return check(sport, power, hr, threshold, DecouplingQualificationCalculator.VI_LIMIT.get(sport), 0.85, 3600);
	}

	private static DecouplingQualificationCalculator.Result check(Sport sport, List<Integer> power, List<Integer> hr,
			Double threshold, double viLimit, double ifLimit, int minSteadySeconds) {
		return DecouplingQualificationCalculator.checkQualification(sport, power, hr, threshold, viLimit, ifLimit, minSteadySeconds);
	}

	@Test
	void qualifiesACleanSteadySession() {
		List<Integer> power = Collections.nCopies(4000, 200);
		List<Integer> hr = Collections.nCopies(4000, 140);
		var result = check(Sport.BIKE, power, hr, 250.0);
		assertThat(result.qualified()).isTrue();
		assertThat(result.reasons()).isEmpty();
		assertThat(result.vi()).isCloseTo(1.0, org.assertj.core.api.Assertions.within(0.01));
		assertThat(result.ifValue()).isCloseTo(0.8, org.assertj.core.api.Assertions.within(0.01));
	}

	@Test
	void tooVariableFailsVi() {
		List<Integer> power = blocks(400, 100, 120, 3600);
		List<Integer> hr = Collections.nCopies(3600, 140);
		var result = check(Sport.BIKE, power, hr, 500.0);
		assertThat(result.reasons()).contains("variable");
	}

	@Test
	void runHasATighterViLimitThanBike() {
		List<Integer> power = blocks(270, 155, 40, 3600);
		List<Integer> hr = Collections.nCopies(3600, 140);
		var bike = check(Sport.BIKE, power, hr, 500.0);
		var run = check(Sport.RUN, power, hr, 500.0);
		assertThat(bike.reasons()).doesNotContain("variable");
		assertThat(run.reasons()).contains("variable");
	}

	@Test
	void configuredViLimitOverridesTheDefault() {
		// The same 1.05-VI surge pattern as above - a bike athlete who's tightened their own
		// limit to 1.0 fails on it even though the design-spec default (1.06) would pass.
		List<Integer> power = blocks(270, 155, 40, 3600);
		List<Integer> hr = Collections.nCopies(3600, 140);
		var result = check(Sport.BIKE, power, hr, 500.0, 1.0, 0.85, 3600);
		assertThat(result.reasons()).contains("variable");
	}

	@Test
	void tooIntenseFailsIf() {
		List<Integer> power = Collections.nCopies(4000, 300);
		List<Integer> hr = Collections.nCopies(4000, 140);
		var result = check(Sport.BIKE, power, hr, 250.0);
		assertThat(result.reasons()).contains("intensity");
	}

	@Test
	void configuredIfLimitOverridesTheDefault() {
		// IF = 300/250 = 1.2, which passes a loosened 1.5 limit even though it fails the default.
		List<Integer> power = Collections.nCopies(4000, 300);
		List<Integer> hr = Collections.nCopies(4000, 140);
		var result = check(Sport.BIKE, power, hr, 250.0, DecouplingQualificationCalculator.VI_LIMIT.get(Sport.BIKE), 1.5, 3600);
		assertThat(result.reasons()).doesNotContain("intensity");
	}

	@Test
	void noThresholdFailsWithItsOwnReasonNotAlsoIntensity() {
		List<Integer> power = Collections.nCopies(4000, 200);
		List<Integer> hr = Collections.nCopies(4000, 140);
		var result = check(Sport.BIKE, power, hr, null);
		assertThat(result.reasons()).contains("no_threshold").doesNotContain("intensity");
	}

	@Test
	void tooShortFailsBelow60Minutes() {
		List<Integer> power = Collections.nCopies(DecouplingQualificationCalculator.MIN_STEADY_SECONDS - 1, 200);
		List<Integer> hr = Collections.nCopies(power.size(), 140);
		var result = check(Sport.BIKE, power, hr, 250.0);
		assertThat(result.reasons()).contains("short");
	}

	@Test
	void configuredMinSteadyMinutesOverridesTheDefault() {
		// 45 minutes fails the default 60-min floor but passes a loosened 30-min one.
		List<Integer> power = Collections.nCopies(45 * 60, 200);
		List<Integer> hr = Collections.nCopies(power.size(), 140);
		var result = check(Sport.BIKE, power, hr, 250.0, DecouplingQualificationCalculator.VI_LIMIT.get(Sport.BIKE), 0.85, 30 * 60);
		assertThat(result.reasons()).doesNotContain("short");
	}

	@Test
	void insufficientHrCoverage() {
		List<Integer> power = Collections.nCopies(4000, 200);
		List<Integer> hr = new ArrayList<>(Collections.nCopies(3800, null));
		hr.addAll(Collections.nCopies(200, 140));
		var result = check(Sport.BIKE, power, hr, 250.0);
		assertThat(result.reasons()).contains("hr_coverage");
	}

	@Test
	void insufficientPowerCoverage() {
		List<Integer> power = new ArrayList<>(Collections.nCopies(3800, null));
		power.addAll(Collections.nCopies(200, 200));
		List<Integer> hr = Collections.nCopies(4000, 140);
		var result = check(Sport.BIKE, power, hr, 250.0);
		assertThat(result.reasons()).contains("power_coverage");
	}

	// ---- DurabilityCalculator ----

	@Test
	void nonBikeRunSportReturnsNoRows() {
		List<Integer> power = Collections.nCopies(4000, 200);
		assertThat(DurabilityCalculator.computeRows(Sport.SWIM, power, sequence(4000))).isEmpty();
	}

	@Test
	void lowPowerCoverageReturnsNoRows() {
		List<Integer> power = new ArrayList<>(Collections.nCopies(3900, null));
		power.addAll(Collections.nCopies(100, 200));
		assertThat(DurabilityCalculator.computeRows(Sport.BIKE, power, sequence(4000))).isEmpty();
	}

	@Test
	void thresholdZeroMatchesThePlainMeanMax() {
		List<Integer> power = new ArrayList<>();
		for (int i = 0; i < 4000; i++) {
			power.add(200 + (i % 50));
		}
		var rows = DurabilityCalculator.computeRows(Sport.BIKE, power, sequence(4000));
		var fresh20min = rows.stream().filter(r -> r.threshold() == 0 && r.windowS() == 1200).findFirst().orElseThrow();
		double expected = Math.round(DurationCurveCalculator.bestAverage(power, 1200));
		assertThat(fresh20min.power()).isEqualTo((int) expected);
	}

	@Test
	void thresholdReachedTooCloseToTheEndWritesNoRowForThatCell() {
		List<Integer> power = new ArrayList<>(Collections.nCopies(3100, 1000));
		power.addAll(Collections.nCopies(100, 200));
		var rows = DurabilityCalculator.computeRows(Sport.BIKE, power, sequence(3200));
		assertThat(rows).noneMatch(r -> r.threshold() == 3000 && r.windowS() == 300);
		assertThat(rows).anyMatch(r -> r.threshold() == 1000 && r.windowS() == 300);
	}

	@Test
	void runUsesMinutesBasisAndBikeUsesKj() {
		List<Integer> bikePower = Collections.nCopies(4000, 1000);
		var bikeRows = DurabilityCalculator.computeRows(Sport.BIKE, bikePower, sequence(4000));
		assertThat(bikeRows).allMatch(r -> r.basis().equals("kj"));
		assertThat(bikeRows.stream().map(r -> r.threshold()).distinct().sorted().toList()).containsExactly(0, 1000, 2000, 3000);

		List<Integer> runPower = Collections.nCopies(95 * 60, 250);
		var runRows = DurabilityCalculator.computeRows(Sport.RUN, runPower, sequence(95 * 60));
		assertThat(runRows).allMatch(r -> r.basis().equals("minutes"));
		assertThat(runRows.stream().map(r -> r.threshold()).distinct().sorted().toList()).containsExactly(0, 60, 90);
	}

	private static List<Integer> sequence(int n) {
		List<Integer> t = new ArrayList<>(n);
		for (int i = 0; i < n; i++) {
			t.add(i);
		}
		return t;
	}

	private static List<Integer> blocks(int high, int low, int blockSize, int total) {
		List<Integer> values = new ArrayList<>(total);
		while (values.size() < total) {
			for (int i = 0; i < blockSize && values.size() < total; i++) {
				values.add(high);
			}
			for (int i = 0; i < blockSize && values.size() < total; i++) {
				values.add(low);
			}
		}
		return values;
	}
}
