package com.cadence.api.activities.calc;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** No prior test existed for this calculator - added alongside the rollingMean extraction
 * (issue #485) so the refactor has a regression guard. */
class NormalizedPowerCalculatorTest {

	private static List<Integer> constant(int value, int n) {
		return IntStream.range(0, n).mapToObj(i -> value).toList();
	}

	@Test
	void emptySeriesGivesNull() {
		assertThat(NormalizedPowerCalculator.compute(List.of())).isNull();
	}

	@Test
	void shorterThanWindowAveragesEverything() {
		assertThat(NormalizedPowerCalculator.compute(Arrays.asList(100, 200, 300))).isEqualTo(200.0);
	}

	@Test
	void constantPowerGivesThatSamePower() {
		assertThat(NormalizedPowerCalculator.compute(constant(200, 100))).isEqualTo(200.0);
	}

	@Test
	void rollingMeanMatchesAHandComputedWindow() {
		List<Integer> series = new java.util.ArrayList<>(constant(100, 30));
		series.addAll(constant(200, 30));
		List<Double> rolling = NormalizedPowerCalculator.rollingMean(series, 30);
		assertThat(rolling).hasSize(series.size() - 30 + 1);
		assertThat(rolling.get(0)).isEqualTo(100.0);
		assertThat(rolling.get(rolling.size() - 1)).isEqualTo(200.0);
	}

	@Test
	void nullSamplesAreZeroFilled() {
		List<Double> rolling = NormalizedPowerCalculator.rollingMean(Arrays.asList(null, null), 30);
		assertThat(rolling).containsExactly(0.0);
	}

	@Test
	void computeUsesRollingMeanUnderTheHood() {
		List<Integer> series = IntStream.range(0, 90).mapToObj(i -> 150 + i % 50).collect(Collectors.toList());
		List<Double> rolling = NormalizedPowerCalculator.rollingMean(series, 30);
		double sumFourth = rolling.stream().mapToDouble(r -> r * r * r * r).sum();
		double expected = Math.pow(sumFourth / rolling.size(), 0.25);
		assertThat(NormalizedPowerCalculator.compute(series)).isEqualTo(expected);
	}
}
