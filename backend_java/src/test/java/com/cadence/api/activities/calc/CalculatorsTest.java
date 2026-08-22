package com.cadence.api.activities.calc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.cadence.api.athletes.Zone;
import com.cadence.api.common.domain.Sport;
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

	private static List<Integer> indices(int size) {
		List<Integer> t = new ArrayList<>(size);
		for (int i = 0; i < size; i++) {
			t.add(i);
		}
		return t;
	}

	@Test
	void bestPaceConstantPaceReturnsThatPace() {
		// 1 km every 300 seconds, 10 km total -> 300 sec/km throughout.
		List<Double> series = new ArrayList<>();
		for (int i = 0; i <= 3000; i++) {
			series.add(i / 300.0);
		}
		assertThat(PaceBestEffortCalculator.bestPaceSecondsPerKm(indices(series.size()), series, 1.0))
				.isCloseTo(300.0, within(0.1));
	}

	@Test
	void bestPaceFindsTheGenuinelyFastestWindowNotTheFirstOne() {
		// 0, 0.5, 1.5, 2.5, 3.0 km at t=0..4. The fastest 1km split is the single second
		// from t=1 to t=2 (exactly 1.0 km in 1 second), not the first qualifying window
		// found (t=0 to t=2, 1.5km in 2 seconds).
		List<Double> series = List.of(0.0, 0.5, 1.5, 2.5, 3.0);
		assertThat(PaceBestEffortCalculator.bestPaceSecondsPerKm(indices(series.size()), series, 1.0))
				.isCloseTo(1.0, within(0.001));
	}

	@Test
	void bestPaceReturnsNullWhenTargetDistanceIsNeverReached() {
		List<Double> series = new ArrayList<>();
		for (int i = 0; i <= 600; i++) {
			series.add(i / 300.0); // 2 km total
		}
		assertThat(PaceBestEffortCalculator.bestPaceSecondsPerKm(indices(series.size()), series, 5.0)).isNull();
	}

	@Test
	void bestPaceForwardFillsGapsInsteadOfTreatingThemAsAReset() {
		// A null sample (brief GPS dropout) should read as "distance unchanged," not zero -
		// same convention the activity's total distance already relies on. The full 2.0 km
		// span (index 0 to 4) takes 4 seconds, so the pace is 4 / 2.0 = 2.0 sec/km.
		List<Double> series = Arrays.asList(0.0, 0.5, null, null, 2.0);
		assertThat(PaceBestEffortCalculator.bestPaceSecondsPerKm(indices(series.size()), series, 2.0))
				.isCloseTo(2.0, within(0.001));
	}

	@Test
	void bestPaceUsesRealElapsedTimeNotSampleIndexForSparseRecording() {
		// Some devices ("smart"/adaptive recording) log a sample every few seconds instead
		// of every second. 2 km covered over samples at t=0, 60, 120 (real elapsed time
		// 120s) must come out as 60 sec/km - not 2 sec/km, which is what you'd get from
		// mistaking the 2-sample gap (index 0 to index 2) for 2 seconds.
		List<Integer> t = List.of(0, 60, 120);
		List<Double> series = List.of(0.0, 1.0, 2.0);
		assertThat(PaceBestEffortCalculator.bestPaceSecondsPerKm(t, series, 2.0)).isCloseTo(60.0, within(0.001));
	}

	// The dual of the bestPace* tests above: fixed *time* target, variable *distance* window,
	// instead of fixed distance/variable time.

	@Test
	void bestPaceOverDurationConstantPaceReturnsThatPace() {
		// 1 km every 60 seconds, sustained for a full hour -> 60 sec/km throughout.
		List<Double> series = new ArrayList<>();
		for (int i = 0; i <= 3600; i++) {
			series.add(i / 60.0);
		}
		assertThat(PaceBestEffortCalculator.bestPaceSecondsPerKmOverDuration(indices(series.size()), series, 3600))
				.isCloseTo(60.0, within(0.1));
	}

	@Test
	void bestPaceOverDurationReturnsNullWhenTargetDurationIsNeverReached() {
		List<Double> series = new ArrayList<>();
		for (int i = 0; i < 100; i++) {
			series.add(i / 60.0); // only ~99 seconds of data
		}
		assertThat(PaceBestEffortCalculator.bestPaceSecondsPerKmOverDuration(indices(series.size()), series, 3600))
				.isNull();
	}

	@Test
	void bestPaceOverDurationUsesRealElapsedTimeNotSampleIndexForSparseRecording() {
		// 10 km covered over samples at t=0 and t=3600 (real elapsed time exactly one hour)
		// must come out as 360 sec/km.
		List<Integer> t = List.of(0, 3600);
		List<Double> series = List.of(0.0, 10.0);
		assertThat(PaceBestEffortCalculator.bestPaceSecondsPerKmOverDuration(t, series, 3600))
				.isCloseTo(360.0, within(0.001));
	}

	@Test
	void bestPaceOverDurationForwardFillsGapsInsteadOfTreatingThemAsAReset() {
		List<Integer> t = indices(3601);
		List<Double> series = new ArrayList<>(Collections.nCopies(3601, null));
		series.set(0, 0.0);
		series.set(3600, 60.0); // 60 km in one hour -> 60 sec/km, everything in between missing
		assertThat(PaceBestEffortCalculator.bestPaceSecondsPerKmOverDuration(t, series, 3600))
				.isCloseTo(60.0, within(0.001));
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

	@Test
	void trimpWeightsMinutesByZoneNumber() {
		// All 60 minutes at 100% of threshold -> Z4 Threshold (91-105%, zone 4) -> 60 * 4 = 240.
		List<Zone> zones = List.of(
				new Zone("Z1 Recovery", 0, 55), new Zone("Z2 Endurance", 56, 75), new Zone("Z3 Tempo", 76, 90),
				new Zone("Z4 Threshold", 91, 105), new Zone("Z5 VO2max", 106, 150));
		Map<String, Integer> secondsPerZone = Map.of("Z4 Threshold", 3600);
		assertThat(TrimpCalculator.compute(secondsPerZone, zones)).isEqualTo(240.0);
	}

	@Test
	void trimpNullWithoutHrData() {
		assertThat(TrimpCalculator.compute(null, List.of())).isNull();
	}

	@Test
	void trainingEffectLabelNullReturnsEmptyString() {
		assertThat(TrainingEffectLabel.of(null)).isEmpty();
	}

	@Test
	void trainingEffectLabelMapsGarminsDocumentedScale() {
		assertThat(TrainingEffectLabel.of(0.0)).isEqualTo("No Benefit");
		assertThat(TrainingEffectLabel.of(0.9)).isEqualTo("No Benefit");
		assertThat(TrainingEffectLabel.of(1.0)).isEqualTo("Minor Benefit");
		assertThat(TrainingEffectLabel.of(1.9)).isEqualTo("Minor Benefit");
		assertThat(TrainingEffectLabel.of(2.0)).isEqualTo("Maintaining");
		assertThat(TrainingEffectLabel.of(2.9)).isEqualTo("Maintaining");
		assertThat(TrainingEffectLabel.of(3.0)).isEqualTo("Improving");
		assertThat(TrainingEffectLabel.of(3.9)).isEqualTo("Improving");
		assertThat(TrainingEffectLabel.of(4.0)).isEqualTo("Highly Improving");
		assertThat(TrainingEffectLabel.of(4.9)).isEqualTo("Highly Improving");
		assertThat(TrainingEffectLabel.of(5.0)).isEqualTo("Overreaching");
	}

	@Test
	void runningPowerSanitizerDropsSamplesOverTheCeiling() {
		List<Integer> series = Arrays.asList(240, 245, 8826, 236, 243);
		List<Integer> sanitized = RunningPowerSanitizer.sanitize(series, Sport.RUN, 1000);
		assertThat(sanitized).containsExactly(240, 245, null, 236, 243);
	}

	@Test
	void runningPowerSanitizerLeavesSamplesAtOrUnderTheCeilingAlone() {
		List<Integer> series = List.of(240, 1000, 999);
		assertThat(RunningPowerSanitizer.sanitize(series, Sport.RUN, 1000))
				.containsExactly(240, 1000, 999);
	}

	@Test
	void runningPowerSanitizerLeavesNullSamplesAlone() {
		List<Integer> series = Arrays.asList((Integer) null, 240);
		assertThat(RunningPowerSanitizer.sanitize(series, Sport.RUN, 1000))
				.containsExactly(null, 240);
	}

	@Test
	void runningPowerSanitizerIsANoOpForCycling() {
		List<Integer> series = List.of(240, 8826, 236);
		assertThat(RunningPowerSanitizer.sanitize(series, Sport.BIKE, 1000))
				.containsExactly(240, 8826, 236);
	}

	@Test
	void environmentSanitizerDropsHumidityReadingsThatAreExactlyZero() {
		List<Integer> humidity = Arrays.asList(55, 0, 60);
		assertThat(EnvironmentSanitizer.sanitizeHumidity(humidity)).containsExactly(55, null, 60);
	}

	@Test
	void environmentSanitizerDropsTheAirTempSampleAlongsideAZeroHumidityReading() {
		// 0C alone is a plausible cold-weather reading - only suspicious paired with the
		// impossible 0% humidity from the same failed sensor read.
		List<Double> airTemp = Arrays.asList(5.0, 0.0, 6.0);
		List<Integer> humidity = Arrays.asList(55, 0, 60);
		assertThat(EnvironmentSanitizer.sanitizeAirTemp(airTemp, humidity)).containsExactly(5.0, null, 6.0);
	}

	@Test
	void environmentSanitizerKeepsAGenuinelyColdAirTempWhenHumidityIsReal() {
		List<Double> airTemp = List.of(0.0);
		List<Integer> humidity = List.of(45);
		assertThat(EnvironmentSanitizer.sanitizeAirTemp(airTemp, humidity)).containsExactly(0.0);
	}

	@Test
	void environmentSanitizerLeavesNullHumiditySamplesAlone() {
		List<Integer> humidity = Arrays.asList((Integer) null, 60);
		assertThat(EnvironmentSanitizer.sanitizeHumidity(humidity)).containsExactly(null, 60);
	}
}
