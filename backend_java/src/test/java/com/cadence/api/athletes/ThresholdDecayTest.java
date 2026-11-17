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

	// The shared primitives decayLoadRatio/decayTarget: the native-boundary versions of the
	// ratio/target math simulateEnabled keeps inline for its per-day loop, added for
	// ThresholdHistoryCalculator.upcomingDrop's and the summary's once-per-call projections
	// (issue #485). Mirrors the Python backend's test_threshold_decay.py::DecayPrimitiveTests.

	@Test
	void decayLoadRatioIsTodayOverAnchor() {
		LocalDate asOf = ANCHOR.plusDays(30);
		assertThat(ThresholdDecay.decayLoadRatio(Map.of(ANCHOR, 100.0, asOf, 50.0), ANCHOR, asOf)).isEqualTo(0.5);
	}

	@Test
	void decayLoadRatioClampsToTheUnitInterval() {
		LocalDate asOf = ANCHOR.plusDays(30);
		assertThat(ThresholdDecay.decayLoadRatio(Map.of(ANCHOR, 100.0, asOf, 250.0), ANCHOR, asOf)).isEqualTo(1.0);
		assertThat(ThresholdDecay.decayLoadRatio(Map.of(ANCHOR, 100.0, asOf, -5.0), ANCHOR, asOf)).isEqualTo(0.0);
	}

	@Test
	void decayLoadRatioIsOneWithNoBaselineLoadAtTheAnchor() {
		// Open question 3: L(a) < 1 -> ratio 1.0, whatever today's load says - including a
		// missing anchor day (load 0.0 < 1).
		LocalDate asOf = ANCHOR.plusDays(30);
		assertThat(ThresholdDecay.decayLoadRatio(Map.of(ANCHOR, 0.5, asOf, 0.0), ANCHOR, asOf)).isEqualTo(1.0);
		assertThat(ThresholdDecay.decayLoadRatio(Map.of(asOf, 50.0), ANCHOR, asOf)).isEqualTo(1.0);
	}

	@Test
	void decayLoadRatioMissingTodayIsZero() {
		LocalDate asOf = ANCHOR.plusDays(30);
		assertThat(ThresholdDecay.decayLoadRatio(Map.of(ANCHOR, 100.0), ANCHOR, asOf)).isEqualTo(0.0);
	}

	@Test
	void decayTargetSuccessorAboveFloorWins() {
		// ratio 1 -> amplitude exactly minPct (3) -> floor 260 * 0.97 = 252.2; the successor
		// (255) is above it, so it survives.
		assertThat(ThresholdDecay.decayTarget(ThresholdField.FTP, 260.0, 255.0, 1.0, SETTINGS)).isEqualTo(255.0);
	}

	@Test
	void decayTargetSuccessorBelowFloorIsClampedToTheFloor() {
		assertThat(ThresholdDecay.decayTarget(ThresholdField.FTP, 260.0, 200.0, 1.0, SETTINGS))
				.isEqualTo(260.0 * (1 - SETTINGS.minPct() / 100.0));
	}

	@Test
	void decayTargetFloorTracksTheRatioAmplitude() {
		// A far-below successor keeps the floor in charge, exposing the ratio -> amplitude map:
		// minPct + (maxPct - minPct) * (1 - ratio).
		assertThat(ThresholdDecay.decayTarget(ThresholdField.FTP, 260.0, 100.0, 1.0, SETTINGS))
				.isEqualTo(260.0 * (1 - 3.0 / 100.0));
		assertThat(ThresholdDecay.decayTarget(ThresholdField.FTP, 260.0, 100.0, 0.5, SETTINGS))
				.isEqualTo(260.0 * (1 - 7.5 / 100.0));
		assertThat(ThresholdDecay.decayTarget(ThresholdField.FTP, 260.0, 100.0, 0.0, SETTINGS))
				.isEqualTo(260.0 * (1 - 12.0 / 100.0));
	}

	@Test
	void decayTargetNoSuccessorIsTheMaxPctFloorRegardlessOfRatio() {
		// The day loop's own no-successor fallback: full maxPct amplitude, ratio ignored.
		assertThat(ThresholdDecay.decayTarget(ThresholdField.FTP, 260.0, null, 1.0, SETTINGS))
				.isEqualTo(260.0 * (1 - SETTINGS.maxPct() / 100.0));
		assertThat(ThresholdDecay.decayTarget(ThresholdField.FTP, 260.0, null, 0.5, SETTINGS))
				.isEqualTo(260.0 * (1 - SETTINGS.maxPct() / 100.0));
	}

	@Test
	void decayTargetClampsAnOutOfRangeRatio() {
		// The day loop's ratio is always in [0, 1]; decayTarget clamps too, so an out-of-range
		// caller can't push the floor above the anchor (which would ease a threshold upwards -
		// or, for pace, faster). A far-below successor keeps the floor in charge so the clamp
		// shows through.
		assertThat(ThresholdDecay.decayTarget(ThresholdField.FTP, 260.0, 100.0, 1.5, SETTINGS))
				.isEqualTo(ThresholdDecay.decayTarget(ThresholdField.FTP, 260.0, 100.0, 1.0, SETTINGS));
		assertThat(ThresholdDecay.decayTarget(ThresholdField.FTP, 260.0, 100.0, -0.5, SETTINGS))
				.isEqualTo(ThresholdDecay.decayTarget(ThresholdField.FTP, 260.0, 100.0, 0.0, SETTINGS));
		// Unclamped, ratio=1.5 would floor pace at 240/1.015 - FASTER than the anchor; clamped
		// it stays at the minPct floor, slower.
		assertThat(ThresholdDecay.decayTarget(ThresholdField.THRESHOLD_PACE, 240.0, 300.0, 1.5, SETTINGS))
				.isCloseTo(240.0 / (1 - 3.0 / 100.0), org.assertj.core.data.Offset.offset(1e-6));
	}

	@Test
	void decayTargetZeroSuccessorCountsAsNoSuccessor() {
		// No real candidate can imply 0, and a missing map entry defaulting to 0.0 is the
		// natural "not found" idiom - so 0.0 takes the no-successor fallback on every field
		// instead of dividing by zero in toEffort for THRESHOLD_PACE.
		assertThat(ThresholdDecay.decayTarget(ThresholdField.FTP, 260.0, 0.0, 1.0, SETTINGS))
				.isEqualTo(260.0 * (1 - SETTINGS.maxPct() / 100.0));
		assertThat(ThresholdDecay.decayTarget(ThresholdField.THRESHOLD_PACE, 240.0, 0.0, 1.0, SETTINGS))
				.isCloseTo(240.0 / (1 - 12.0 / 100.0), org.assertj.core.data.Offset.offset(1e-6));
	}

	@Test
	void decayTargetPaceComesOutSlowerNeverFaster() {
		// The effort-space conversion (1/value) means the floor is anchor / (1 - amplitude/100)
		// seconds/km - SLOWER than the anchor. A native-space `anchor * (1 - amplitude/100)`
		// would have eased the pace faster, which is wrong. A far-slower successor keeps the
		// floor in charge so the ratio -> amplitude map shows through.
		assertThat(ThresholdDecay.decayTarget(ThresholdField.THRESHOLD_PACE, 240.0, 300.0, 1.0, SETTINGS))
				.isCloseTo(240.0 / (1 - 3.0 / 100.0), org.assertj.core.data.Offset.offset(1e-6));
		assertThat(ThresholdDecay.decayTarget(ThresholdField.THRESHOLD_PACE, 240.0, 300.0, 0.0, SETTINGS))
				.isCloseTo(240.0 / (1 - 12.0 / 100.0), org.assertj.core.data.Offset.offset(1e-6));
		// No successor at all: the day loop's own fallback (maxPct, ratio ignored) - still
		// slower than the anchor.
		assertThat(ThresholdDecay.decayTarget(ThresholdField.THRESHOLD_PACE, 240.0, null, 1.0, SETTINGS))
				.isCloseTo(240.0 / (1 - 12.0 / 100.0), org.assertj.core.data.Offset.offset(1e-6));
		// A better successor (faster pace) wins outright.
		assertThat(ThresholdDecay.decayTarget(ThresholdField.THRESHOLD_PACE, 240.0, 230.0, 1.0, SETTINGS))
				.isCloseTo(230.0, org.assertj.core.data.Offset.offset(1e-6));
	}

	@Test
	void decayTargetMatchesTheDayLoopsOwnTarget() {
		// The primitives must agree with what simulateEnabled's inline math actually drives v
		// towards: flat load at ratio r, and after enough days v sits on round(target). Every
		// branch of the target formula is covered - no successor (the fallback in charge), a
		// far-below successor (the floor in charge), and an above-floor successor (the
		// successor in charge) - so a tuning change to either copy's formula desyncs here.
		LocalDate expiry = ANCHOR.plusDays(SETTINGS.windowDays() + 1L);
		LocalDate end = expiry.plusDays(400);
		for (double ratio : new double[] { 0.0, 0.25, 1.0 }) {
			for (Double successor : new Double[] { null, 200.0, 255.0 }) {
				Map<LocalDate, Double> load = flatLoad(ANCHOR, ratio, end);
				List<ThresholdDecay.DecayCandidate> candidates =
						successor == null ? List.of() : persistentLowSuccessor(ANCHOR, successor, end);
				List<ThresholdDecay.Step> steps = ThresholdDecay.simulate(ThresholdField.FTP, SETTINGS, 260.0, ANCHOR,
						ThresholdDecay.MEASURED, candidates, List.of(), load, ANCHOR, end);
				double expected = ThresholdDecay.decayTarget(ThresholdField.FTP, 260.0, successor, ratio, SETTINGS);
				assertThat(steps.get(steps.size() - 1).value())
						.as("ratio=" + ratio + " successor=" + successor)
						.isEqualTo(Math.round(expected));
			}
		}
	}

	@Test
	void decayTargetMatchesTheDayLoopsOwnTargetForPace() {
		// Same cross-check as decayTargetMatchesTheDayLoopsOwnTarget, but for threshold_pace -
		// the one field with a non-trivial effort conversion (1/value), exactly where a Java/
		// Python desync in the native<->effort boundary would hide. 300 (slower than the 240
		// anchor) keeps the floor in charge; 245 (also slower than the anchor, so no upward
		// reset) still beats the floor, so the successor takes over instead.
		LocalDate expiry = ANCHOR.plusDays(SETTINGS.windowDays() + 1L);
		LocalDate end = expiry.plusDays(400);
		for (double ratio : new double[] { 0.0, 1.0 }) {
			for (Double successor : new Double[] { null, 300.0, 245.0 }) {
				Map<LocalDate, Double> load = flatLoad(ANCHOR, ratio, end);
				List<ThresholdDecay.DecayCandidate> candidates =
						successor == null ? List.of() : persistentLowSuccessor(ANCHOR, successor, end);
				List<ThresholdDecay.Step> steps = ThresholdDecay.simulate(ThresholdField.THRESHOLD_PACE, SETTINGS,
						240.0, ANCHOR, ThresholdDecay.MEASURED, candidates, List.of(), load, ANCHOR, end);
				double expected =
						ThresholdDecay.decayTarget(ThresholdField.THRESHOLD_PACE, 240.0, successor, ratio, SETTINGS);
				assertThat(steps.get(steps.size() - 1).value())
						.as("ratio=" + ratio + " successor=" + successor)
						.isEqualTo(Math.round(expected));
			}
		}
	}
}
