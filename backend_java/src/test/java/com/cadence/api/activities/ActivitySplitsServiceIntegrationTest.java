package com.cadence.api.activities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.cadence.api.activities.dto.ActivitySplitsResponse;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.support.IntegrationTest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Mirrors the Python backend's activities/tests/test_splits.py - laps, 1km/5km fixed-distance
 * splits and elevation-section splits from the activity's own recorded altitude, with no
 * goal/target reference or delta (unlike RaceSplitsServiceIntegrationTest). */
class ActivitySplitsServiceIntegrationTest extends IntegrationTest {

	@Autowired
	private ActivitySplitsService activitySplitsService;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private ActivityRepository activityRepository;
	@Autowired
	private LapRepository lapRepository;
	@Autowired
	private RecordRepository recordRepository;

	private User newAthlete(String email) {
		User user = new User();
		user.setEmail(email);
		user.setName("Athlete");
		user.setPassword("irrelevant-for-this-test");
		return userRepository.save(user);
	}

	private Activity newActivity(User athlete, Instant startDate, double distanceKm, int movingTime) {
		Activity activity = new Activity();
		activity.setAthlete(athlete);
		activity.setSport(Sport.RUN);
		activity.setName("Activity");
		activity.setStartDate(startDate);
		activity.setDistanceKm(distanceKm);
		activity.setMovingTime(movingTime);
		return activityRepository.save(activity);
	}

	private void addPaceRecords(Activity activity, double distanceKm, double paceSPerKm, int stepS, Integer power) {
		int totalS = (int) (distanceKm * paceSPerKm);
		Instant start = activity.getStartDate();
		for (int t = 0; t <= totalS; t += stepS) {
			double km = t / paceSPerKm;
			Record record = new Record();
			record.setId(new RecordId(activity.getId(), start.plusSeconds(t)));
			record.setActivity(activity);
			record.setT(t);
			record.setDistanceKm(Math.round(km * 10000) / 10000.0);
			record.setHeartrate(140);
			record.setPower(power);
			recordRepository.save(record);
		}
	}

	/** Flat for 1km, then a 30m climb over 0.6km, then flat again - same shape as the Python
	 * backend's activities/tests/test_sections.py fixture. */
	private double altitude(double km) {
		if (km <= 1.0) return 50.0;
		if (km <= 1.6) return 50.0 + (km - 1.0) * 50.0;
		return 80.0;
	}

	private void addRecordsWithClimb(Activity activity, double distanceKm, double paceSPerKm, int stepS) {
		int totalS = (int) (distanceKm * paceSPerKm);
		Instant start = activity.getStartDate();
		for (int t = 0; t <= totalS; t += stepS) {
			double km = t / paceSPerKm;
			Record record = new Record();
			record.setId(new RecordId(activity.getId(), start.plusSeconds(t)));
			record.setActivity(activity);
			record.setT(t);
			record.setDistanceKm(Math.round(km * 10000) / 10000.0);
			record.setAltitude(altitude(km));
			recordRepository.save(record);
		}
	}

	@Test
	void multipleLapsReportHasLapsTrueWithNoRefOrDeltaFields() {
		User athlete = newAthlete("act-splits-laps-multi@example.cc");
		Activity activity = newActivity(athlete, Instant.parse("2026-10-12T08:00:00Z"), 10.0, 3000);

		Lap lap1 = new Lap();
		lap1.setActivity(activity);
		lap1.setIndex(1);
		lap1.setDuration(300);
		lap1.setDistanceKm(1.0);
		lap1.setAvgHr(140);
		lap1.setAvgPower(255);
		lapRepository.save(lap1);

		Lap lap2 = new Lap();
		lap2.setActivity(activity);
		lap2.setIndex(2);
		lap2.setDuration(300);
		lap2.setDistanceKm(1.0);
		lap2.setAvgHr(142);
		lapRepository.save(lap2);

		ActivitySplitsResponse result = activitySplitsService.getSplits(activity, "laps");

		assertThat(result.hasLaps()).isTrue();
		assertThat(result.splits()).hasSize(2);
		assertThat(result.splits().get(0).timeS()).isEqualTo(300);
		assertThat(result.splits().get(0).avgPower()).isEqualTo(255);
		assertThat(result.splits().get(1).avgPower()).isNull();
	}

	@Test
	void singleLapReportsHasLapsFalse() {
		User athlete = newAthlete("act-splits-laps-single@example.cc");
		Activity activity = newActivity(athlete, Instant.parse("2026-10-12T08:00:00Z"), 10.0, 3000);

		Lap lap = new Lap();
		lap.setActivity(activity);
		lap.setIndex(1);
		lap.setDuration(3000);
		lap.setDistanceKm(10.0);
		lapRepository.save(lap);

		assertThat(activitySplitsService.getSplits(activity, "laps").hasLaps()).isFalse();
	}

	@Test
	void oneKmSplitsCoverTheWholeDistanceWithAPartialLastSplit() {
		User athlete = newAthlete("act-splits-1km@example.cc");
		Activity activity = newActivity(athlete, Instant.parse("2026-10-12T08:00:00Z"), 5.5, 1650);
		addPaceRecords(activity, 5.5, 300, 10, 200);

		ActivitySplitsResponse result = activitySplitsService.getSplits(activity, "1");

		assertThat(result.splits()).hasSize(6); // 5 full km + one 0.5km partial
		ActivitySplitsResponse.Split last = result.splits().get(result.splits().size() - 1);
		assertThat(last.endKm() - last.startKm()).isCloseTo(0.5, within(0.02));
		assertThat(last.label()).isEqualTo("Finish");
		assertThat(result.splits().get(0).paceSPerKm()).isCloseTo(300.0, within(2.0));
		assertThat(result.splits().get(0).avgPower()).isEqualTo(200);
	}

	@Test
	void fiveKmSplitsProduceTwoSplits() {
		User athlete = newAthlete("act-splits-5km@example.cc");
		Activity activity = newActivity(athlete, Instant.parse("2026-10-12T08:00:00Z"), 5.5, 1650);
		addPaceRecords(activity, 5.5, 300, 10, null);

		ActivitySplitsResponse result = activitySplitsService.getSplits(activity, "5");

		assertThat(result.splits()).hasSize(2);
		assertThat(result.splits().get(0).endKm()).isCloseTo(5.0, within(0.02));
	}

	@Test
	void elevationSplitsFollowTheActivitysOwnSections() {
		User athlete = newAthlete("act-splits-elevation@example.cc");
		Activity activity = newActivity(athlete, Instant.parse("2026-10-12T08:00:00Z"), 3.0, 900);
		addRecordsWithClimb(activity, 3.0, 300, 10);

		ActivitySplitsResponse result = activitySplitsService.getSplits(activity, "elevation");

		assertThat(result.splits()).extracting(ActivitySplitsResponse.Split::sectionType)
				.containsExactly("flat", "steep_climb", "flat");
		assertThat(result.splits().get(0).label()).isNull();
	}

	@Test
	void elevationSplitsAreEmptyWithNoAltitude() {
		User athlete = newAthlete("act-splits-elevation-missing@example.cc");
		Activity activity = newActivity(athlete, Instant.parse("2026-10-12T08:00:00Z"), 3.0, 900);
		addPaceRecords(activity, 3.0, 300, 10, null);

		ActivitySplitsResponse result = activitySplitsService.getSplits(activity, "elevation");

		assertThat(result.splits()).isEmpty();
		assertThat(result.hasLaps()).isFalse();
		assertThat(result.halves()).isNull();
	}
}
