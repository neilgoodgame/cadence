package com.cadence.api.activities.calc;

import static org.assertj.core.api.Assertions.assertThat;

import com.cadence.api.activities.calc.StreamComparisonCalculator.ComparisonResult;
import com.cadence.api.activities.calc.StreamComparisonCalculator.LagResult;
import com.cadence.api.activities.calc.StreamComparisonCalculator.PowerFit;
import com.cadence.api.activities.calc.StreamComparisonCalculator.Tolerance;
import com.cadence.api.activities.calc.StreamComparisonCalculator.ZoneBucketResult;
import com.cadence.api.athletes.Zone;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;

/**
 * Issue #503 (phase 4): mirrors backend's activities/tests/test_stream_comparison.py - pure
 * computation, no Spring context, so no {@code IntegrationTest} base class.
 */
class StreamComparisonCalculatorTest {

	private static final Offset<Double> TOLERANCE = Offset.offset(0.001);

	// Same non-periodic-signal convention ActivityMergeAlignmentCalculatorTest uses for its own
	// lag-search test - a periodic signal would let a wrong-but-aliased lag score perfectly too.
	// The *100_000 spacing (copied from that test) matters: seeding java.util.Random with small
	// sequential longs produces noticeably autocorrelated output, which confuses the lag search.
	private static int profile(int t) {
		Random rng = new Random((long) t * 100_000);
		return (int) Math.round(80.0 + 20.0 * (rng.nextDouble() * 2 - 1));
	}

	private static Map<Integer, Integer> series(int n, java.util.function.IntUnaryOperator fn) {
		Map<Integer, Integer> series = new HashMap<>();
		for (int t = 0; t < n; t++) {
			series.put(t, fn.applyAsInt(t));
		}
		return series;
	}

	@Test
	void compareComputesBiasMaeRmseAndPearsonOnAConstantOffset() {
		Map<Integer, Integer> a = series(40, t -> 140 + t);
		Map<Integer, Integer> b = series(40, t -> 150 + t); // a constant +10 offset, perfectly correlated

		ComparisonResult result = StreamComparisonCalculator.compare(a, b, Tolerance.absolute(5.0));

		assertThat(result).isNotNull();
		assertThat(result.overlapSeconds()).isEqualTo(40);
		assertThat(result.bias()).isEqualTo(10.0, TOLERANCE);
		assertThat(result.mae()).isEqualTo(10.0, TOLERANCE);
		assertThat(result.rmse()).isEqualTo(10.0, TOLERANCE);
		assertThat(result.pearsonR()).isEqualTo(1.0, TOLERANCE);
		assertThat(result.loaLower()).isEqualTo(10.0, TOLERANCE);
		assertThat(result.loaUpper()).isEqualTo(10.0, TOLERANCE);
		assertThat(result.withinTolerancePct()).isEqualTo(0.0, TOLERANCE); // 10bpm diff, 5bpm tolerance
	}

	@Test
	void compareReturnsNullBelowTheMinimumOverlap() {
		Map<Integer, Integer> a = series(10, t -> 140 + t);
		Map<Integer, Integer> b = series(10, t -> 150 + t);

		assertThat(StreamComparisonCalculator.compare(a, b, Tolerance.absolute(5.0))).isNull();
	}

	@Test
	void compareWithARelativeToleranceMeasuresAgainstSeriesAsOwnValue() {
		Map<Integer, Integer> a = series(40, t -> 200); // constant, so 3% tolerance is a fixed 6
		Map<Integer, Integer> b = series(40, t -> 205); // 5 off - within 3% of 200 (6)

		ComparisonResult result = StreamComparisonCalculator.compare(a, b, Tolerance.relativePct(3.0));

		assertThat(result.withinTolerancePct()).isEqualTo(100.0, TOLERANCE);
	}

	@Test
	void findLagRecoversAKnownInjectedShift() {
		Map<Integer, Integer> a = series(120, StreamComparisonCalculatorTest::profile);
		// b lags a by 7 seconds: b[t] == a[t-7], i.e. b's own series evaluated at (t-7).
		Map<Integer, Integer> b = series(120, t -> profile(t - 7));

		LagResult lag = StreamComparisonCalculator.findLag(a, b);

		assertThat(lag).isNotNull();
		assertThat(lag.lagSeconds()).isEqualTo(7);
		assertThat(lag.correlation()).isGreaterThan(0.99);
	}

	@Test
	void fitPowerScaleOffsetRecoversAKnownLinearRelationship() {
		Map<Integer, Integer> a = series(40, t -> 200 + t);
		Map<Integer, Integer> b = series(40, t -> (int) Math.round(1.1 * (200 + t) + 5)); // scale 1.1, offset 5

		PowerFit fit = StreamComparisonCalculator.fitPowerScaleOffset(a, b);

		assertThat(fit.scale()).isEqualTo(1.1, Offset.offset(0.01));
		assertThat(fit.offset()).isEqualTo(5.0, Offset.offset(1.0));
		assertThat(fit.ratioOfMeans()).isGreaterThan(1.0);
	}

	@Test
	void zoneBreakdownBucketsPairsBySeriesAAsPercentOfReferenceTopDown() {
		// Two bands: Z1 [0,60), Z2 [60,150) - a's values range 50..109 against reference 100, so
		// values below 60% of 100 (i.e. a < 60) fall in Z1, the rest in Z2.
		List<Zone> zones = List.of(new Zone("Z1", 0, 59), new Zone("Z2", 60, 150));
		Map<Integer, Integer> a = series(60, t -> 50 + t); // 50..109
		Map<Integer, Integer> b = series(60, t -> 50 + t + 10); // a constant +10 over a

		List<ZoneBucketResult> buckets = StreamComparisonCalculator.zoneBreakdown(a, b, zones, 100.0);

		assertThat(buckets).hasSize(2);
		ZoneBucketResult z1 = buckets.get(0);
		ZoneBucketResult z2 = buckets.get(1);
		// a<60 for t in [0,10) -> 10 samples in Z1; the remaining 50 in Z2.
		assertThat(z1.sampleCount()).isEqualTo(10);
		assertThat(z2.sampleCount()).isEqualTo(50);
		assertThat(z1.bias()).isEqualTo(10.0, TOLERANCE);
		assertThat(z2.bias()).isEqualTo(10.0, TOLERANCE);
	}

	@Test
	void zoneBreakdownReportsAnEmptyBucketWithNullStatsRatherThanOmittingIt() {
		List<Zone> zones = List.of(new Zone("Z1", 0, 50), new Zone("Z2", 51, 150));
		Map<Integer, Integer> a = series(40, t -> 200 + t); // always >> 50% of reference 10 - all Z2
		Map<Integer, Integer> b = series(40, t -> 205 + t);

		List<ZoneBucketResult> buckets = StreamComparisonCalculator.zoneBreakdown(a, b, zones, 10.0);

		ZoneBucketResult z1 = buckets.get(0);
		assertThat(z1.sampleCount()).isZero();
		assertThat(z1.bias()).isNull();
		assertThat(z1.mae()).isNull();
		assertThat(z1.rmse()).isNull();
	}
}
