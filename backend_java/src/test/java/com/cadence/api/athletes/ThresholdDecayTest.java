package com.cadence.api.athletes;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Plan section 9.1's hand-written unit tests (docs/plans/threshold-decay.md), trimmed to this
 * slice's inputs - no histogram/load-filter/no-circularity cases, since those need the real
 * intensity histogram, deferred to the next #485 slice. Mirrors the Python backend's
 * athletes/test_threshold_decay.py::DecayCurveTests scenario for scenario.
 */
class ThresholdDecayTest {

	private static final ThresholdDecay.DecaySettings SETTINGS =
			new ThresholdDecay.DecaySettings(112, 50, 25.0, 3.0, 12.0, true);
	private static final LocalDate ANCHOR = LocalDate.of(2026, 1, 1);

	private static Map<LocalDate, Double> flatLoad(LocalDate anchor, double ratio, LocalDate through) {
		Map<LocalDate, Double> load = new HashMap<>();
		long days = java.time.temporal.ChronoUnit.DAYS.between(anchor, through);
		for (long i = -5; i < days + 5; i++) {
			load.put(anchor.plusDays(i), i == 0 ? 100.0 : 100.0 * ratio);
		}
		return load;
	}

	/** A successor that never ages out of the trailing window, so `target` stays pinned to
	 * `floor` (the ratio/amplitude math) rather than falling through to the no-successor
	 * {@code A*(1-maxPct)} branch. */
	private static List<ThresholdDecay.DecayCandidate> persistentLowSuccessor(LocalDate anchor, double value, LocalDate through) {
		List<ThresholdDecay.DecayCandidate> out = new ArrayList<>();
		LocalDate d = anchor.plusDays(10);
		int i = 0;
		while (!d.isAfter(through)) {
			out.add(new ThresholdDecay.DecayCandidate(d, value, "succ_" + i, true));
			d = d.plusDays(10);
			i++;
		}
		return out;
	}

	private static double valueOn(List<ThresholdDecay.Step> steps, LocalDate d, double initial) {
		double value = initial;
		for (ThresholdDecay.Step s : steps) {
			if (!s.currentFrom().isAfter(d)) {
				value = s.value();
			}
			else {
				break;
			}
		}
		return value;
	}

	@Test
	void decayCurveMatchesClosedFormAtFourteenTwentyEightFiftySixDays() {
		LocalDate expiry = ANCHOR.plusDays(SETTINGS.windowDays() + 1L);
		LocalDate end = expiry.plusDays(60);
		Map<LocalDate, Double> load = flatLoad(ANCHOR, 1.0, end);
		List<ThresholdDecay.DecayCandidate> candidates = persistentLowSuccessor(ANCHOR, 200.0, end);
		List<ThresholdDecay.Step> steps = ThresholdDecay.simulate(
				ThresholdField.FTP, SETTINGS, 260.0, ANCHOR, ThresholdDecay.MEASURED, candidates, List.of(), load, ANCHOR, end);

		double target = 260.0 * (1 - SETTINGS.minPct() / 100.0);
		double decayFactor = Math.exp(-1.0 / SETTINGS.tauDays());
		LocalDate base = expiry.minusDays(1);
		for (int t : new int[] {14, 28, 56}) {
			double closedForm = target + (260.0 - target) * Math.pow(decayFactor, t);
			double actual = valueOn(steps, base.plusDays(t), 260.0);
			assertThat(actual).as("t=" + t).isCloseTo(Math.round(closedForm), org.assertj.core.data.Offset.offset(1.0));
		}
		List<Double> values = steps.stream().map(ThresholdDecay.Step::value).toList();
		List<Double> sortedDesc = values.stream().sorted((a, b) -> Double.compare(b, a)).distinct().toList();
		assertThat(values).containsExactlyElementsOf(sortedDesc);
	}

	@Test
	void movingTargetContinuity() {
		LocalDate successorDate = ANCHOR.plusDays(SETTINGS.windowDays() + 1L + 10);
		LocalDate end = successorDate.plusDays(SETTINGS.windowDays() + 30L);
		Map<LocalDate, Double> load = flatLoad(ANCHOR, 0.0, end);
		List<ThresholdDecay.DecayCandidate> candidates =
				List.of(new ThresholdDecay.DecayCandidate(successorDate, 240.0, "succ", true));
		List<ThresholdDecay.Step> steps = ThresholdDecay.simulate(
				ThresholdField.FTP, SETTINGS, 260.0, ANCHOR, ThresholdDecay.MEASURED, candidates, List.of(), load, ANCHOR, end);

		double maxStep = (260.0 - 260.0 * (1 - SETTINGS.maxPct() / 100.0)) * (1 - Math.exp(-1.0 / SETTINGS.tauDays()));
		for (int i = 1; i < steps.size(); i++) {
			double delta = Math.abs(steps.get(i - 1).value() - steps.get(i).value());
			assertThat(delta).as("day " + steps.get(i).currentFrom()).isLessThanOrEqualTo(maxStep + 1);
		}
	}

	@Test
	void upwardResetRequiresAtLeastTheCurrentRoundedValue() {
		LocalDate mid = ANCHOR.plusDays(SETTINGS.windowDays() + 1L + 50);
		Map<LocalDate, Double> load = flatLoad(ANCHOR, 0.0, mid.plusDays(5));
		List<ThresholdDecay.Step> upTo = ThresholdDecay.simulate(
				ThresholdField.FTP, SETTINGS, 260.0, ANCHOR, ThresholdDecay.MEASURED, List.of(), List.of(), load, ANCHOR, mid);
		double current = upTo.get(upTo.size() - 1).value();

		LocalDate day = mid.plusDays(1);
		ThresholdDecay.DecayCandidate fails = new ThresholdDecay.DecayCandidate(day, current - 1, "fail", false);
		ThresholdDecay.DecayCandidate passes = new ThresholdDecay.DecayCandidate(day, current, "pass", false);

		List<ThresholdDecay.Step> failSteps = ThresholdDecay.simulate(
				ThresholdField.FTP, SETTINGS, 260.0, ANCHOR, ThresholdDecay.MEASURED, List.of(fails), List.of(), load, ANCHOR, day);
		List<ThresholdDecay.Step> passSteps = ThresholdDecay.simulate(
				ThresholdField.FTP, SETTINGS, 260.0, ANCHOR, ThresholdDecay.MEASURED, List.of(passes), List.of(), load, ANCHOR, day);

		assertThat(failSteps.get(failSteps.size() - 1).kind()).isEqualTo(ThresholdDecay.DECAYED);
		assertThat(passSteps.get(passSteps.size() - 1).kind()).isEqualTo(ThresholdDecay.MEASURED);
		assertThat(passSteps.get(passSteps.size() - 1).activityId()).isEqualTo("pass");
	}

	@Test
	void manualEntryResetsEvenWhenLower() {
		LocalDate mid = ANCHOR.plusDays(SETTINGS.windowDays() + 1L + 50);
		Map<LocalDate, Double> load = flatLoad(ANCHOR, 0.0, mid.plusDays(5));
		List<ThresholdDecay.Step> upTo = ThresholdDecay.simulate(
				ThresholdField.FTP, SETTINGS, 260.0, ANCHOR, ThresholdDecay.MEASURED, List.of(), List.of(), load, ANCHOR, mid);
		double current = upTo.get(upTo.size() - 1).value();
		assertThat(current).isLessThan(260.0);

		LocalDate day = mid.plusDays(1);
		ThresholdDecay.ManualEntry manual = new ThresholdDecay.ManualEntry(day, current - 10);
		List<ThresholdDecay.Step> steps = ThresholdDecay.simulate(
				ThresholdField.FTP, SETTINGS, 260.0, ANCHOR, ThresholdDecay.MEASURED, List.of(), List.of(manual), load, ANCHOR, day);
		assertThat(steps.get(steps.size() - 1).kind()).isEqualTo(ThresholdDecay.MANUAL);
		assertThat(steps.get(steps.size() - 1).value()).isEqualTo(current - 10);
	}

	@Test
	void neverEasesUpwardWhenLoadRecovers() {
		LocalDate expiry = ANCHOR.plusDays(SETTINGS.windowDays() + 1L);
		LocalDate end = expiry.plusDays(60);
		Map<LocalDate, Double> load = flatLoad(ANCHOR, 0.0, end);
		List<ThresholdDecay.Step> baseline = ThresholdDecay.simulate(
				ThresholdField.FTP, SETTINGS, 260.0, ANCHOR, ThresholdDecay.MEASURED, List.of(), List.of(), load, ANCHOR, end);
		List<Double> values = baseline.stream().map(ThresholdDecay.Step::value).toList();
		List<Double> sortedDesc = values.stream().sorted((a, b) -> Double.compare(b, a)).toList();
		assertThat(values).containsExactlyElementsOf(sortedDesc);

		Map<LocalDate, Double> recovered = new HashMap<>(load);
		for (int i = 53; i <= 60; i++) {
			recovered.put(ANCHOR.plusDays(i), 100.0);
		}
		List<ThresholdDecay.Step> recoveredSteps = ThresholdDecay.simulate(
				ThresholdField.FTP, SETTINGS, 260.0, ANCHOR, ThresholdDecay.MEASURED, List.of(), List.of(), recovered, ANCHOR, end);
		assertThat(recoveredSteps.get(recoveredSteps.size() - 1).value())
				.isLessThanOrEqualTo(baseline.get(baseline.size() - 1).value() + 0.0001);
	}

	@Test
	void noSuccessorTargetIsAnchorTimesOneMinusMaxPct() {
		LocalDate expiry = ANCHOR.plusDays(SETTINGS.windowDays() + 1L);
		LocalDate end = expiry.plusDays(400);
		Map<LocalDate, Double> load = flatLoad(ANCHOR, 0.0, end);
		List<ThresholdDecay.Step> steps = ThresholdDecay.simulate(
				ThresholdField.FTP, SETTINGS, 260.0, ANCHOR, ThresholdDecay.MEASURED, List.of(), List.of(), load, ANCHOR, end);
		assertThat(steps.get(steps.size() - 1).value()).isEqualTo(Math.round(260.0 * (1 - SETTINGS.maxPct() / 100.0)));
	}

	@Test
	void loadRatioClampAndAmplitude() {
		LocalDate expiry = ANCHOR.plusDays(SETTINGS.windowDays() + 1L);
		LocalDate end = expiry.plusDays(400);
		List<ThresholdDecay.DecayCandidate> candidates = persistentLowSuccessor(ANCHOR, 200.0, end);

		Map<LocalDate, Double> loadOver = flatLoad(ANCHOR, 2.0, end);
		List<ThresholdDecay.Step> steps = ThresholdDecay.simulate(
				ThresholdField.FTP, SETTINGS, 260.0, ANCHOR, ThresholdDecay.MEASURED, candidates, List.of(), loadOver, ANCHOR, end);
		assertThat(steps.get(steps.size() - 1).value()).isEqualTo(Math.round(260.0 * (1 - SETTINGS.minPct() / 100.0)));

		Map<LocalDate, Double> loadZero = flatLoad(ANCHOR, 0.0, end);
		steps = ThresholdDecay.simulate(
				ThresholdField.FTP, SETTINGS, 260.0, ANCHOR, ThresholdDecay.MEASURED, candidates, List.of(), loadZero, ANCHOR, end);
		assertThat(steps.get(steps.size() - 1).value()).isEqualTo(Math.round(260.0 * (1 - SETTINGS.maxPct() / 100.0)));

		Map<LocalDate, Double> loadTinyAnchor = new HashMap<>(loadZero);
		loadTinyAnchor.put(ANCHOR, 0.5);
		steps = ThresholdDecay.simulate(
				ThresholdField.FTP, SETTINGS, 260.0, ANCHOR, ThresholdDecay.MEASURED, candidates, List.of(), loadTinyAnchor, ANCHOR, end);
		assertThat(steps.get(steps.size() - 1).value()).isEqualTo(Math.round(260.0 * (1 - SETTINGS.minPct() / 100.0)));

		ThresholdDecay.DecaySettings noMinSettings = new ThresholdDecay.DecaySettings(112, 50, 25.0, 0.0, 12.0, true);
		Map<LocalDate, Double> loadMaintained = flatLoad(ANCHOR, 1.0, end);
		steps = ThresholdDecay.simulate(
				ThresholdField.FTP, noMinSettings, 260.0, ANCHOR, ThresholdDecay.MEASURED, candidates, List.of(), loadMaintained, ANCHOR, end);
		assertThat(steps).isEmpty();
	}

	@Test
	void minimumDriftWithMaintainedLoad() {
		LocalDate expiry = ANCHOR.plusDays(SETTINGS.windowDays() + 1L);
		LocalDate end = expiry.plusDays(60);
		Map<LocalDate, Double> load = flatLoad(ANCHOR, 1.0, end);
		List<ThresholdDecay.DecayCandidate> candidates = persistentLowSuccessor(ANCHOR, 200.0, end);
		List<ThresholdDecay.Step> steps = ThresholdDecay.simulate(
				ThresholdField.FTP, SETTINGS, 260.0, ANCHOR, ThresholdDecay.MEASURED, candidates, List.of(), load, ANCHOR, end);

		LocalDate base = expiry.minusDays(1);
		assertThat(valueOn(steps, base.plusDays(14), 260.0)).isEqualTo(257.0);
		assertThat(valueOn(steps, base.plusDays(28), 260.0)).isEqualTo(255.0);
		assertThat(valueOn(steps, base.plusDays(56), 260.0)).isEqualTo(253.0);
	}

	@Test
	void paceDecaysSlowerAndFloorsCorrectly() {
		LocalDate expiry = ANCHOR.plusDays(SETTINGS.windowDays() + 1L);
		LocalDate end = expiry.plusDays(400);
		Map<LocalDate, Double> load = flatLoad(ANCHOR, 0.0, end);
		List<ThresholdDecay.Step> steps = ThresholdDecay.simulate(
				ThresholdField.THRESHOLD_PACE, SETTINGS, 240.0, ANCHOR, ThresholdDecay.MEASURED, List.of(), List.of(), load, ANCHOR, end);
		List<Double> values = steps.stream().map(ThresholdDecay.Step::value).toList();
		List<Double> sortedAsc = values.stream().sorted().toList();
		assertThat(values).containsExactlyElementsOf(sortedAsc);
		assertThat(steps.get(steps.size() - 1).value()).isEqualTo(273.0);
	}

	@Test
	void disabledModeReproducesSuccessorTakeoverShape() {
		ThresholdDecay.DecaySettings disabledSettings = new ThresholdDecay.DecaySettings(30, 50, 25.0, 3.0, 12.0, false);
		ThresholdDecay.DecayCandidate later = new ThresholdDecay.DecayCandidate(ANCHOR.plusDays(5), 240.0, "act_b", false);
		LocalDate end = ANCHOR.plusDays(disabledSettings.windowDays() + 5L);
		List<ThresholdDecay.Step> steps = ThresholdDecay.simulate(
				ThresholdField.FTP, disabledSettings, 260.0, ANCHOR, ThresholdDecay.MEASURED, List.of(later), List.of(), Map.of(), ANCHOR, end);
		assertThat(steps).hasSize(1);
		assertThat(steps.get(0).kind()).isEqualTo(ThresholdDecay.MEASURED);
		assertThat(steps.get(0).value()).isEqualTo(240.0);
		assertThat(steps.get(0).activityId()).isEqualTo("act_b");
		assertThat(steps.get(0).effectiveFrom()).isEqualTo(later.date());
	}

	@Test
	void manualAnchorBeforeExpiryNotReplacedByLowerLaterCandidate() {
		LocalDate manualDate = ANCHOR;
		ThresholdDecay.ManualEntry manual = new ThresholdDecay.ManualEntry(manualDate, 250.0);
		ThresholdDecay.DecayCandidate lower = new ThresholdDecay.DecayCandidate(manualDate.plusDays(5), 230.0, "low", false);
		LocalDate end = manualDate.plusDays(30);
		List<ThresholdDecay.Step> steps = ThresholdDecay.simulate(
				ThresholdField.FTP, SETTINGS, null, null, null, List.of(lower), List.of(manual), Map.of(), manualDate, end);
		assertThat(steps).hasSize(1);
		assertThat(steps.get(0).kind()).isEqualTo(ThresholdDecay.MANUAL);
		assertThat(steps.get(0).value()).isEqualTo(250.0);
	}

	@Test
	void coldStartWithNoSeedAnchor() {
		LocalDate start = ANCHOR;
		ThresholdDecay.DecayCandidate candidate = new ThresholdDecay.DecayCandidate(start.plusDays(3), 200.0, "first", false);
		List<ThresholdDecay.Step> steps = ThresholdDecay.simulate(
				ThresholdField.FTP, SETTINGS, null, null, null, List.of(candidate), List.of(), Map.of(), start, start.plusDays(10));
		assertThat(steps).hasSize(1);
		assertThat(steps.get(0).kind()).isEqualTo(ThresholdDecay.MEASURED);
		assertThat(steps.get(0).value()).isEqualTo(200.0);
	}

	/** The slice-3 extension: resuming a suffix well after the anchor's own date (e.g. the daily
	 * job, resuming from "the day after the latest row's current_from") must continue decaying
	 * from that row's own value, not jump back up to the anchor. */
	@Test
	void seedVValueResumesFromAnAlreadyDecayedValueNotTheAnchor() {
		LocalDate expiry = ANCHOR.plusDays(SETTINGS.windowDays() + 1L);
		LocalDate fullEnd = expiry.plusDays(30);
		Map<LocalDate, Double> load = flatLoad(ANCHOR, 0.0, fullEnd);
		List<ThresholdDecay.Step> full = ThresholdDecay.simulate(
				ThresholdField.FTP, SETTINGS, 260.0, ANCHOR, ThresholdDecay.MEASURED, List.of(), List.of(), load, ANCHOR, fullEnd);
		LocalDate resumeFrom = full.get(full.size() - 1).currentFrom();
		double alreadyDecayedValue = full.get(full.size() - 1).value();
		assertThat(alreadyDecayedValue).isLessThan(260.0);

		LocalDate resumeStart = resumeFrom.plusDays(1);
		LocalDate resumeEnd = fullEnd.plusDays(20);
		List<ThresholdDecay.Step> resumed = ThresholdDecay.simulate(ThresholdField.FTP, SETTINGS, 260.0, ANCHOR,
				ThresholdDecay.MEASURED, List.of(), List.of(), load, resumeStart, resumeEnd, alreadyDecayedValue);
		List<ThresholdDecay.Step> onePass = ThresholdDecay.simulate(
				ThresholdField.FTP, SETTINGS, 260.0, ANCHOR, ThresholdDecay.MEASURED, List.of(), List.of(), load, ANCHOR, resumeEnd);

		assertThat(valueOn(resumed, resumeEnd, alreadyDecayedValue)).isEqualTo(valueOn(onePass, resumeEnd, 260.0));

		// Without the seedVValue fix, resuming would incorrectly restart from the anchor (260).
		List<ThresholdDecay.Step> wrongResume = ThresholdDecay.simulate(
				ThresholdField.FTP, SETTINGS, 260.0, ANCHOR, ThresholdDecay.MEASURED, List.of(), List.of(), load, resumeStart, resumeEnd);
		assertThat(wrongResume.get(0).value()).isNotEqualTo(resumed.get(0).value());
	}

	/** The slice-3 extension: a manual entry whose ledger row already exists (because it's
	 * outside this particular resimulation's own trigger) must still protect against a lower
	 * later candidate (1.1), but must not produce a second Step for itself. */
	@Test
	void alreadyRecordedManualEntrySuppressesWithoutDuplicatingAStep() {
		LocalDate manualDate = ANCHOR;
		ThresholdDecay.ManualEntry manual = new ThresholdDecay.ManualEntry(manualDate, 250.0, null, true);
		ThresholdDecay.DecayCandidate lower = new ThresholdDecay.DecayCandidate(manualDate.plusDays(5), 230.0, "low", false);
		LocalDate end = manualDate.plusDays(30);
		List<ThresholdDecay.Step> steps = ThresholdDecay.simulate(
				ThresholdField.FTP, SETTINGS, null, null, null, List.of(lower), List.of(manual), Map.of(), manualDate, end);
		// No Step at all - the manual entry isn't re-emitted, and the lower candidate correctly
		// fails the upward-reset test against it, so nothing changes.
		assertThat(steps).isEmpty();
	}
}
