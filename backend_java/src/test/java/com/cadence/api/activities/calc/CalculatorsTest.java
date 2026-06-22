package com.cadence.api.activities.calc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.cadence.api.athletes.Zone;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CalculatorsTest {

	@Test
	void normalizedPowerOfConstantSeriesEqualsThatConstant() {
		List<Integer> series = Collections.nCopies(60, 200);
		Double np = NormalizedPowerCalculator.compute(series);
		assertThat(np).isCloseTo(200.0, within(0.01));
	}

	@Test
	void normalizedPowerShorterThanWindowFallsBackToMean() {
		List<Integer> series = List.of(100, 200, 300);
		Double np = NormalizedPowerCalculator.compute(series);
		assertThat(np).isCloseTo(200.0, within(0.01));
	}

	@Test
	void normalizedPowerTreatsNullSamplesAsZero() {
		List<Integer> series = Arrays.asList(null, null, 100);
		Double np = NormalizedPowerCalculator.compute(series);
		assertThat(np).isCloseTo(100.0 / 3, within(0.01));
	}

	@Test
	void bestAverageFindsHighestWindow() {
		List<Integer> series = List.of(10, 20, 30, 40, 50);
		Double best = DurationCurveCalculator.bestAverage(series, 2);
		assertThat(best).isCloseTo(45.0, within(0.01)); // avg(40, 50)
	}

	@Test
	void bestAverageNullWhenSeriesShorterThanWindow() {
		List<Integer> series = List.of(1, 2);
		assertThat(DurationCurveCalculator.bestAverage(series, 5)).isNull();
	}

	@Test
	void durationCurveOnlyEmitsPointsTheSeriesIsLongEnoughFor() {
		List<Integer> series = Collections.nCopies(10, 100);
		Map<Integer, Double> points = DurationCurveCalculator.compute(series, List.of(5, 60, 300));
		assertThat(points).containsOnlyKeys(5);
		assertThat(points.get(5)).isEqualTo(100.0);
	}

	@Test
	void durationCurveExtendsToFullSeriesWhenLongerThanTheStandardWindows() {
		// An activity over an hour: the curve should add one more point at the full
		// length, valued as the whole-activity average - exactly what extendsTo on the
		// resulting DurationCurve entity documents happening.
		List<Integer> series = new ArrayList<>(Collections.nCopies(3600, 200));
		series.addAll(Collections.nCopies(1800, 100));
		Map<Integer, Double> points = DurationCurveCalculator.compute(series, List.of(5, 60, 3600));
		assertThat(points).containsKey(5400);
		assertThat(points.get(5400)).isCloseTo((200.0 * 3600 + 100.0 * 1800) / 5400, within(0.1));
	}

	@Test
	void durationCurveDoesNotExtendWhenSeriesIsNoLongerThanTheStandardWindows() {
		List<Integer> series = Collections.nCopies(3600, 100);
		Map<Integer, Double> points = DurationCurveCalculator.compute(series, List.of(5, 60, 3600));
		assertThat(points).containsOnlyKeys(5, 60, 3600);
	}

	@Test
	void bestPaceConstantPaceReturnsThatPace() {
		// 1 km every 300 seconds, 10 km total -> 300 sec/km throughout.
		List<Double> series = new ArrayList<>();
		for (int i = 0; i <= 3000; i++) {
			series.add(i / 300.0);
		}
		assertThat(PaceBestEffortCalculator.bestPaceSecondsPerKm(series, 1.0)).isCloseTo(300.0, within(0.1));
	}

	@Test
	void bestPaceFindsTheGenuinelyFastestWindowNotTheFirstOne() {
		// 0, 0.5, 1.5, 2.5, 3.0 km at t=0..4. The fastest 1km split is the single second
		// from t=1 to t=2 (exactly 1.0 km in 1 second), not the first qualifying window
		// found (t=0 to t=2, 1.5km in 2 seconds).
		List<Double> series = List.of(0.0, 0.5, 1.5, 2.5, 3.0);
		assertThat(PaceBestEffortCalculator.bestPaceSecondsPerKm(series, 1.0)).isCloseTo(1.0, within(0.001));
	}

	@Test
	void bestPaceReturnsNullWhenTargetDistanceIsNeverReached() {
		List<Double> series = new ArrayList<>();
		for (int i = 0; i <= 600; i++) {
			series.add(i / 300.0); // 2 km total
		}
		assertThat(PaceBestEffortCalculator.bestPaceSecondsPerKm(series, 5.0)).isNull();
	}

	@Test
	void bestPaceForwardFillsGapsInsteadOfTreatingThemAsAReset() {
		// A null sample (brief GPS dropout) should read as "distance unchanged," not zero -
		// same convention the activity's total distance already relies on. The full 2.0 km
		// span (index 0 to 4) takes 4 seconds, so the pace is 4 / 2.0 = 2.0 sec/km.
		List<Double> series = Arrays.asList(0.0, 0.5, null, null, 2.0);
		assertThat(PaceBestEffortCalculator.bestPaceSecondsPerKm(series, 2.0)).isCloseTo(2.0, within(0.001));
	}

	@Test
	void powerBasedTssAtThresholdForOneHourIsOneHundred() {
		Integer tss = TssCalculator.powerBased(200.0, 200, 3600);
		assertThat(tss).isEqualTo(100);
	}

	@Test
	void powerBasedTssNullWithoutThreshold() {
		assertThat(TssCalculator.powerBased(200.0, null, 3600)).isNull();
	}

	@Test
	void hrBasedTssWeightsByZoneMidpoint() {
		List<Zone> zones = List.of(new Zone("Z1", 0, 60), new Zone("Z2", 61, 100));
		Map<String, Integer> secondsPerZone = Map.of("Z1", 1800, "Z2", 1800); // 30 min each
		int tss = TssCalculator.hrBased(secondsPerZone, zones);
		// Z1 midpoint 30%: 0.5h * 30 = 15; Z2 midpoint 80.5%: 0.5h * 80.5 = 40.25; total ~55
		assertThat(tss).isEqualTo(55);
	}

	@Test
	void secondsPerZoneBucketsByPercentOfThreshold() {
		List<Zone> zones = List.of(new Zone("Z1", 0, 55), new Zone("Z2", 56, 100));
		List<Integer> hrSeries = Arrays.asList(100, 100, 200, null); // threshold 200 -> 50%, 50%, 100%
		Map<String, Integer> result = TssCalculator.secondsPerZone(hrSeries, zones, 200.0);
		assertThat(result).containsEntry("Z1", 2).containsEntry("Z2", 1);
	}
}
