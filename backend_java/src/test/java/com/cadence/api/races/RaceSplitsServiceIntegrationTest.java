package com.cadence.api.races;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.cadence.api.activities.Activity;
import com.cadence.api.activities.Lap;
import com.cadence.api.activities.LapRepository;
import com.cadence.api.activities.Record;
import com.cadence.api.activities.RecordId;
import com.cadence.api.activities.RecordRepository;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.races.dto.SplitsResponse;
import com.cadence.api.support.IntegrationTest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Mirrors the Python backend's races/tests/test_splits.py - laps, 1km and 5km fixed-distance
 * splits, has_laps fallback, and the goal-vs-average reference pace. */
class RaceSplitsServiceIntegrationTest extends IntegrationTest {

	@Autowired
	private RaceSplitsService raceSplitsService;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private com.cadence.api.activities.ActivityRepository activityRepository;
	@Autowired
	private RaceRepository raceRepository;
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
		activity.setName("Race activity");
		activity.setStartDate(startDate);
		activity.setDistanceKm(distanceKm);
		activity.setMovingTime(movingTime);
		return activityRepository.save(activity);
	}

	private void addConstantPaceRecords(Activity activity, double distanceKm, double paceSPerKm, int stepS) {
		int totalS = (int) (distanceKm * paceSPerKm);
		Instant start = activity.getStartDate();
		for (int t = 0; t <= totalS; t += stepS) {
			Record record = new Record();
			record.setId(new RecordId(activity.getId(), start.plusSeconds(t)));
			record.setActivity(activity);
			record.setT(t);
			record.setDistanceKm(Math.round((t / paceSPerKm) * 10000) / 10000.0);
			record.setHeartrate(140);
			recordRepository.save(record);
		}
	}

	@Test
	void multipleLapsReportHasLapsTrue() {
		User athlete = newAthlete("splits-laps-multi@example.cc");
		Activity activity = newActivity(athlete, Instant.parse("2026-10-12T08:00:00Z"), 10.0, 3000);
		Race race = new Race();
		race.setAthlete(athlete);
		race.setName("10k");
		race.setDate(java.time.LocalDate.of(2026, 10, 12));
		race.setActivity(activity);
		race.setDistanceKm(10.0);
		race = raceRepository.save(race);

		Lap lap1 = new Lap();
		lap1.setActivity(activity);
		lap1.setIndex(1);
		lap1.setDuration(300);
		lap1.setDistanceKm(1.0);
		lap1.setAvgHr(140);
		lapRepository.save(lap1);

		Lap lap2 = new Lap();
		lap2.setActivity(activity);
		lap2.setIndex(2);
		lap2.setDuration(300);
		lap2.setDistanceKm(1.0);
		lap2.setAvgHr(142);
		lapRepository.save(lap2);

		SplitsResponse result = raceSplitsService.getSplits(race, "laps");

		assertThat(result.hasLaps()).isTrue();
		assertThat(result.splits()).hasSize(2);
		assertThat(result.splits().get(0).timeS()).isEqualTo(300);
		assertThat(result.splits().get(1).startKm()).isEqualTo(1.0);
	}

	@Test
	void singleLapReportsHasLapsFalse() {
		User athlete = newAthlete("splits-laps-single@example.cc");
		Activity activity = newActivity(athlete, Instant.parse("2026-10-12T08:00:00Z"), 10.0, 3000);
		Race race = new Race();
		race.setAthlete(athlete);
		race.setName("10k");
		race.setDate(java.time.LocalDate.of(2026, 10, 12));
		race.setActivity(activity);
		race = raceRepository.save(race);

		Lap lap = new Lap();
		lap.setActivity(activity);
		lap.setIndex(1);
		lap.setDuration(3000);
		lap.setDistanceKm(10.0);
		lapRepository.save(lap);

		assertThat(raceSplitsService.getSplits(race, "laps").hasLaps()).isFalse();
	}

	@Test
	void noLinkedActivityReturnsEmptySplits() {
		User athlete = newAthlete("splits-no-activity@example.cc");
		Race race = new Race();
		race.setAthlete(athlete);
		race.setName("No activity race");
		race.setDate(java.time.LocalDate.of(2026, 10, 12));
		race = raceRepository.save(race);

		SplitsResponse result = raceSplitsService.getSplits(race, "1");

		assertThat(result.splits()).isEmpty();
		assertThat(result.hasLaps()).isFalse();
	}

	@Test
	void oneKmSplitsCoverTheWholeDistanceWithAPartialLastSplit() {
		User athlete = newAthlete("splits-1km@example.cc");
		Activity activity = newActivity(athlete, Instant.parse("2026-10-12T08:00:00Z"), 5.5, 1650);
		addConstantPaceRecords(activity, 5.5, 300, 10); // steady 5:00/km
		Race race = new Race();
		race.setAthlete(athlete);
		race.setName("5.5k time trial");
		race.setDate(java.time.LocalDate.of(2026, 10, 12));
		race.setActivity(activity);
		race.setDistanceKm(5.5);
		race.setGoalTime(27 * 60 + 30); // exactly 300s/km goal pace
		race = raceRepository.save(race);

		SplitsResponse result = raceSplitsService.getSplits(race, "1");

		assertThat(result.splits()).hasSize(6); // 5 full km + one 0.5km partial
		SplitsResponse.Split last = result.splits().get(result.splits().size() - 1);
		assertThat(last.endKm() - last.startKm()).isCloseTo(0.5, within(0.02));
		assertThat(last.label()).isEqualTo("Finish");
		assertThat(result.ref()).isEqualTo("goal");
		assertThat(result.splits().get(0).paceSPerKm()).isCloseTo(300.0, within(2.0));
	}

	@Test
	void fiveKmSplitsProduceTwoSplits() {
		User athlete = newAthlete("splits-5km@example.cc");
		Activity activity = newActivity(athlete, Instant.parse("2026-10-12T08:00:00Z"), 5.5, 1650);
		addConstantPaceRecords(activity, 5.5, 300, 10);
		Race race = new Race();
		race.setAthlete(athlete);
		race.setName("5.5k time trial");
		race.setDate(java.time.LocalDate.of(2026, 10, 12));
		race.setActivity(activity);
		race.setDistanceKm(5.5);
		race = raceRepository.save(race);

		SplitsResponse result = raceSplitsService.getSplits(race, "5");

		assertThat(result.splits()).hasSize(2);
		assertThat(result.splits().get(0).endKm()).isCloseTo(5.0, within(0.02));
	}
}
