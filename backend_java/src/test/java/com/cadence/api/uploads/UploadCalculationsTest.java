package com.cadence.api.uploads;

import static org.assertj.core.api.Assertions.assertThat;

import com.cadence.api.uploads.parsing.ParsedActivity;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Mirrors the Python backend's uploads/tests/test_processing_math.py::TotalAscentDescentTests. */
class UploadCalculationsTest {

	private static ParsedActivity.Sample sample(double altitude) {
		return new ParsedActivity.Sample(0, null, null, altitude, null, null, null, null, null, null, null, null, null, null, null);
	}

	@Test
	void sensorNoiseOnFlatGroundDoesNotAccumulate() {
		// +/-1m back-and-forth noise around a flat baseline for several minutes - there's no
		// real net elevation change here. Unsmoothed, this used to sum every single positive
		// micro-fluctuation (see UploadCalculations.smoothedAltitudes' javadoc for the
		// real-world case this was found from: a 515m-vs-actual-~400m Leeds Marathon ascent).
		double[] noiseCycle = {0, 1, 0, -1};
		List<ParsedActivity.Sample> samples = new ArrayList<>();
		for (int i = 0; i < 200; i++) {
			samples.add(sample(100 + noiseCycle[i % noiseCycle.length]));
		}

		assertThat(UploadCalculations.totalAscent(samples)).isLessThan(10);
		assertThat(UploadCalculations.totalDescent(samples)).isLessThan(10);
	}

	@Test
	void sustainedClimbSurvivesSmoothing() {
		// A genuine, sustained ~100m climb over 200 samples must still register close to the
		// true gain - smoothing should filter noise, not real elevation change.
		List<ParsedActivity.Sample> samples = new ArrayList<>();
		for (int i = 0; i < 200; i++) {
			samples.add(sample(100 + i * 0.5));
		}

		assertThat(UploadCalculations.totalAscent(samples)).isGreaterThan(80);
	}

	@Test
	void fewerThanTwoAltitudeSamplesReturnsNull() {
		assertThat(UploadCalculations.totalAscent(List.of(sample(100)))).isNull();
		assertThat(UploadCalculations.totalDescent(List.of(sample(100)))).isNull();
	}
}
