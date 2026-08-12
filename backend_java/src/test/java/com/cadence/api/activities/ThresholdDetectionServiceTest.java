package com.cadence.api.activities;

import static org.assertj.core.api.Assertions.assertThat;

import com.cadence.api.common.domain.Sport;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/** ThresholdDetectionService.detect - the actual detection formulas: bike FTP is 95% of the
 * best 20-minute power, run criticalRunPower/thresholdPace come directly from the best
 * 60-minute effort. Only ever suggests an *increase* (or, for pace, a *faster* time). Plain
 * unit test - the service has no repository dependencies, no Spring context needed. */
class ThresholdDetectionServiceTest {

	private final ThresholdDetectionService service = new ThresholdDetectionService();

	private static List<Integer> constantSeries(int value, int size) {
		return Collections.nCopies(size, value);
	}

	private static List<Integer> indices(int size) {
		List<Integer> result = new ArrayList<>(size);
		for (int i = 0; i < size; i++) {
			result.add(i);
		}
		return result;
	}

	private static List<Double> noDistance(int size) {
		return new ArrayList<>(Collections.nCopies(size, null));
	}

	private static List<Integer> noPower(int size) {
		return new ArrayList<>(Collections.nCopies(size, null));
	}

	@Test
	void bikeSuggestsFtpAt95PercentOfBest20MinPower() {
		Activity activity = new Activity();
		activity.setSport(Sport.BIKE);
		activity.setFtpSnapshot(200);
		// A steady 280W for the full 20-minute window -> implied FTP = round(0.95 * 280) = 266.
		List<Integer> powerSeries = constantSeries(280, 1200);
		service.detect(activity, powerSeries, indices(1200), noDistance(1200));
		assertThat(activity.getSuggestedFtp()).isEqualTo(266);
	}

	@Test
	void bikeDoesNotSuggestADecrease() {
		Activity activity = new Activity();
		activity.setSport(Sport.BIKE);
		activity.setFtpSnapshot(300);
		List<Integer> powerSeries = constantSeries(200, 1200); // implies ~190W - below the 300 snapshot
		service.detect(activity, powerSeries, indices(1200), noDistance(1200));
		assertThat(activity.getSuggestedFtp()).isNull();
	}

	@Test
	void bikeActivityShorterThanThe20MinWindowSuggestsNothing() {
		Activity activity = new Activity();
		activity.setSport(Sport.BIKE);
		activity.setFtpSnapshot(200);
		List<Integer> powerSeries = constantSeries(280, 600); // only 10 minutes
		service.detect(activity, powerSeries, indices(600), noDistance(600));
		assertThat(activity.getSuggestedFtp()).isNull();
	}

	@Test
	void runSuggestsCriticalRunPowerDirectlyFromBest60MinPower() {
		Activity activity = new Activity();
		activity.setSport(Sport.RUN);
		activity.setCriticalRunPowerSnapshot(250);
		List<Integer> powerSeries = constantSeries(300, 3600);
		service.detect(activity, powerSeries, indices(3600), noDistance(3600));
		assertThat(activity.getSuggestedCriticalRunPower()).isEqualTo(300);
	}

	@Test
	void runSuggestsThresholdPaceFromBest60MinPace() {
		Activity activity = new Activity();
		activity.setSport(Sport.RUN);
		activity.setThresholdPaceSnapshot("4:30");
		List<Integer> t = indices(3601);
		List<Double> distanceKmSeries = new ArrayList<>(Collections.nCopies(3601, null));
		distanceKmSeries.set(0, 0.0);
		distanceKmSeries.set(3600, 15.0); // 15km in an hour -> 240 sec/km -> "4:00"
		service.detect(activity, noPower(3601), t, distanceKmSeries);
		assertThat(activity.getSuggestedThresholdPace()).isEqualTo("4:00");
	}

	@Test
	void runDoesNotSuggestASlowerPace() {
		Activity activity = new Activity();
		activity.setSport(Sport.RUN);
		activity.setThresholdPaceSnapshot("4:00");
		List<Integer> t = indices(3601);
		List<Double> distanceKmSeries = new ArrayList<>(Collections.nCopies(3601, null));
		distanceKmSeries.set(0, 0.0);
		distanceKmSeries.set(3600, 12.0); // 300 sec/km ("5:00") - slower than the 4:00 snapshot
		service.detect(activity, noPower(3601), t, distanceKmSeries);
		assertThat(activity.getSuggestedThresholdPace()).isEqualTo("");
	}

	@Test
	void multisportAndOtherSportsNeverSuggestAnything() {
		Activity activity = new Activity();
		activity.setSport(Sport.SWIM);
		service.detect(activity, constantSeries(300, 3600), indices(3600), noDistance(3600));
		assertThat(activity.getSuggestedFtp()).isNull();
		assertThat(activity.getSuggestedCriticalRunPower()).isNull();
		assertThat(activity.getSuggestedThresholdPace()).isEqualTo("");
	}
}
