package com.cadence.api.courses;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CourseProcessingTest {

	private static List<CourseProcessing.TrackPoint> flatThenHillThenFlat(int n, double hillGainM) {
		List<CourseProcessing.TrackPoint> points = new ArrayList<>();
		int third = n / 3;
		for (int i = 0; i < n; i++) {
			double distanceKm = i * 0.01;
			double elevation;
			if (i < third) {
				elevation = 100.0;
			}
			else if (i < 2 * third) {
				elevation = 100.0 + hillGainM * (i - third) / third;
			}
			else {
				elevation = 100.0 + hillGainM;
			}
			points.add(new CourseProcessing.TrackPoint(51.5 + i * 0.0001, -0.1, elevation, distanceKm));
		}
		return points;
	}

	@Test
	void smallNoiseIsSmoothedAway() {
		List<CourseProcessing.TrackPoint> points = new ArrayList<>();
		for (int i = 0; i < 60; i++) {
			points.add(new CourseProcessing.TrackPoint(0, 0, 100.0 + (i % 2 == 0 ? 1.0 : -1.0), i * 0.01));
		}
		Double[] smoothed = CourseProcessing.smoothedElevations(points);
		for (Double v : smoothed) {
			if (v != null) assertThat(v).isCloseTo(100.0, within(1.0));
		}
	}

	@Test
	void noiseUnderThresholdDoesNotAccumulate() {
		// Oscillates +/-0.5m around 100 (1m peak-to-peak) - under the 2m noise threshold, so
		// the baseline never moves and nothing accumulates.
		Double[] values = new Double[40];
		for (int i = 0; i < 40; i++) values[i] = 100.0 + (i % 2 == 0 ? 0.5 : -0.5);
		int[] gainLoss = CourseProcessing.gainLoss(values);
		assertThat(gainLoss).containsExactly(0, 0);
	}

	@Test
	void realClimbIsCounted() {
		Double[] values = {100.0, 105.0, 110.0, 108.0, 100.0};
		int[] gainLoss = CourseProcessing.gainLoss(values);
		assertThat(gainLoss[0]).isEqualTo(10);
		assertThat(gainLoss[1]).isEqualTo(10);
	}

	@Test
	void detectsOneClimbAboveThresholds() {
		List<CourseProcessing.TrackPoint> points = flatThenHillThenFlat(300, 50.0);
		Double[] smoothed = CourseProcessing.smoothedElevations(points);
		List<Climb> climbs = CourseProcessing.detectClimbs(points, smoothed);
		assertThat(climbs).hasSize(1);
		assertThat(climbs.get(0).gainM()).isGreaterThanOrEqualTo(45);
		assertThat(climbs.get(0).avgGradePct()).isGreaterThanOrEqualTo(1.5);
	}

	@Test
	void subThresholdBumpIsNotAClimb() {
		List<CourseProcessing.TrackPoint> points = flatThenHillThenFlat(300, 5.0); // below the 12m gain floor
		Double[] smoothed = CourseProcessing.smoothedElevations(points);
		assertThat(CourseProcessing.detectClimbs(points, smoothed)).isEmpty();
	}

	@Test
	void straightLineSimplifiesToTwoPoints() {
		List<CourseProcessing.TrackPoint> points = new ArrayList<>();
		for (int i = 0; i < 600; i++) points.add(new CourseProcessing.TrackPoint(51.5 + i * 0.0001, -0.1, null, i * 0.01));
		assertThat(CourseProcessing.simplifyRoute(points)).hasSize(2);
	}

	@Test
	void alreadySmallRouteIsUntouched() {
		List<CourseProcessing.TrackPoint> points = new ArrayList<>();
		for (int i = 0; i < 10; i++) points.add(new CourseProcessing.TrackPoint(51.5 + i * 0.0001, -0.1, null, i * 0.01));
		assertThat(CourseProcessing.simplifyRoute(points)).hasSize(10);
	}

	@Test
	void startEqualsFinishIsALoop() {
		List<CourseProcessing.TrackPoint> points = List.of(
				new CourseProcessing.TrackPoint(51.5, -0.1, null, 0.0),
				new CourseProcessing.TrackPoint(51.55, -0.1, null, 5.0),
				new CourseProcessing.TrackPoint(51.5001, -0.1001, null, 10.0));
		assertThat(CourseProcessing.isLoop(points)).isTrue();
	}

	@Test
	void pointToPointIsNotALoop() {
		List<CourseProcessing.TrackPoint> points = List.of(
				new CourseProcessing.TrackPoint(51.5, -0.1, null, 0.0),
				new CourseProcessing.TrackPoint(51.6, -0.2, null, 10.0));
		assertThat(CourseProcessing.isLoop(points)).isFalse();
	}

	@Test
	void tooSmallFileIsRejected() throws IOException {
		byte[] raw = Files.readAllBytes(fixture("too_small.gpx"));
		assertThatThrownBy(() -> CourseProcessing.parseGpxTrack(raw)).isInstanceOf(CourseProcessing.GpxProcessingException.class);
	}

	@Test
	void unparseableBytesRaise() {
		assertThatThrownBy(() -> CourseProcessing.parseGpxTrack("not gpx at all".getBytes()))
				.isInstanceOf(CourseProcessing.GpxProcessingException.class);
	}

	@Test
	void oversizedFileIsRejected() {
		byte[] raw = new byte[(int) CourseProcessing.MAX_GPX_BYTES + 1];
		assertThatThrownBy(() -> CourseProcessing.parseGpxTrack(raw)).isInstanceOf(CourseProcessing.GpxProcessingException.class);
	}

	/**
	 * Parity check against the Python backend's reference output for the exact same fixture
	 * (see backend/courses/fixtures/hill_loop.gpx, shared verbatim with
	 * backend/courses/tests/test_processing.py) - README §3.6 requires the two backends agree
	 * on distance/gain/loss/climbs within 0.5%. Reference values computed once by running the
	 * Python backend's own process_track_points against this file:
	 * distance_km=2.247, elevation_gain_m=25, elevation_loss_m=25, elevation_max_m=125,
	 * elevation_min_m=100, is_loop=true, one climb (start_km=0.19, end_km=1.66, gain_m=25,
	 * avg_grade_pct=1.7).
	 */
	@Test
	void hillLoopFixtureMatchesThePythonBackendWithin0Point5Percent() throws IOException {
		byte[] raw = Files.readAllBytes(fixture("hill_loop.gpx"));
		List<CourseProcessing.TrackPoint> points = CourseProcessing.parseGpxTrack(raw);
		assertThat(points.size()).isGreaterThan(50);

		CourseProcessing.ProcessedTrack track = CourseProcessing.processTrackPoints(points);

		assertThat(track.distanceKm()).isCloseTo(2.247, within(2.247 * 0.005));
		assertThat(track.elevationGainM()).isEqualTo(25);
		assertThat(track.elevationLossM()).isEqualTo(25);
		assertThat(track.elevationMaxM()).isEqualTo(125);
		assertThat(track.elevationMinM()).isEqualTo(100);
		assertThat(track.isLoop()).isTrue();
		assertThat(track.climbs()).hasSize(1);
		Climb climb = track.climbs().get(0);
		assertThat(climb.gainM()).isEqualTo(25);
		assertThat(climb.startKm()).isCloseTo(0.19, within(0.05));
		assertThat(climb.endKm()).isCloseTo(1.66, within(0.05));
		assertThat(track.routePolyline()).hasSizeLessThanOrEqualTo(500);
		assertThat(track.elevationProfile()).hasSizeLessThanOrEqualTo(201);
	}

	private static Path fixture(String name) {
		return Path.of("src/test/resources/gpx-fixtures", name);
	}
}
