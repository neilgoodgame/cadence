package com.cadence.api.activities;

import static org.assertj.core.api.Assertions.assertThat;

import com.cadence.api.common.domain.Sport;
import com.cadence.api.support.IntegrationTest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Mirrors the Python backend's activities/tests/test_recompute_stats.py. */
class DerivedStatsRecomputeServiceIntegrationTest extends IntegrationTest {

	@Autowired
	private DerivedStatsRecomputeService derivedStatsRecomputeService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private ActivityRepository activityRepository;

	@Autowired
	private RecordRepository recordRepository;

	private User newAthlete(String email) {
		User user = new User();
		user.setEmail(email);
		user.setName("Test Athlete");
		user.setPassword("irrelevant-for-this-test");
		user.setLthr(160);
		return userRepository.save(user);
	}

	private Activity newActivity(User athlete, int movingTime) {
		Activity activity = new Activity();
		activity.setAthlete(athlete);
		activity.setSport(Sport.BIKE);
		activity.setName("Backfill target");
		activity.setStartDate(Instant.parse("2026-01-01T08:00:00Z"));
		activity.setMovingTime(movingTime);
		return activityRepository.save(activity);
	}

	private Activity newRunActivity(User athlete, int movingTime) {
		Activity activity = new Activity();
		activity.setAthlete(athlete);
		activity.setSport(Sport.RUN);
		activity.setName("Run backfill target");
		activity.setStartDate(Instant.parse("2026-01-01T08:00:00Z"));
		activity.setMovingTime(movingTime);
		return activityRepository.save(activity);
	}

	@Test
	void backfillsStatsFromStoredRecords() {
		User athlete = newAthlete("backfill@example.cc");
		Activity activity = newActivity(athlete, 3600);
		for (int t = 0; t < 3600; t++) {
			Record record = new Record();
			record.setId(new RecordId(activity.getId(), activity.getStartDate().plusSeconds(t)));
			record.setActivity(activity);
			record.setT(t);
			record.setPower(200);
			record.setCadence(85);
			record.setSpeed(8.0);
			record.setAltitude((double) (100 + (t % 10)));
			record.setHeartrate(160);
			recordRepository.save(record);
		}

		Activity updated = derivedStatsRecomputeService.recomputeForActivity(activity.getId());

		assertThat(updated.getMaxPower()).isEqualTo(200);
		assertThat(updated.getAvgCadence()).isEqualTo(85);
		assertThat(updated.getMaxCadence()).isEqualTo(85);
		assertThat(updated.getMaxSpeed()).isCloseTo(28.8, org.assertj.core.data.Offset.offset(0.1));
		assertThat(updated.getElevationMin()).isEqualTo(100);
		assertThat(updated.getElevationMax()).isEqualTo(109);
		// A 0-9m sawtooth every 10 samples is sensor-noise-scale, not real elevation change -
		// smoothed, it nets out to near zero rather than summing every micro-fluctuation.
		assertThat(updated.getAscent()).isLessThan(20);
		assertThat(updated.getTotalDescent()).isLessThan(20);
		assertThat(updated.getCalories()).isNotNull();
		// All samples at 100% of LTHR (160) -> Z4 Threshold -> 60 min * zone 4 = 240.
		assertThat(updated.getTrimp()).isEqualTo(240.0);
	}

	@Test
	void missingSourceDataLeavesFieldsNull() {
		User athlete = newAthlete("no-data@example.cc");
		Activity activity = newActivity(athlete, 60);
		Record record = new Record();
		record.setId(new RecordId(activity.getId(), activity.getStartDate()));
		record.setActivity(activity);
		record.setT(0);
		recordRepository.save(record);

		Activity updated = derivedStatsRecomputeService.recomputeForActivity(activity.getId());

		assertThat(updated.getMaxPower()).isNull();
		assertThat(updated.getAvgCadence()).isNull();
		assertThat(updated.getCalories()).isNull();
	}

	// Regression test for a real bug found live: a Stryd ambient-sensor pairing failure reports
	// a flat 0.0C/0% for an entire activity instead of omitting the reading - 25 of a real
	// account's 572 Stryd-equipped activities affected. EnvironmentSanitizer treats that as
	// missing data, not a real reading (see its Javadoc), but only recomputing an activity
	// actually corrects an already-stored stale 0/0 - it isn't touched by anything else.
	@Test
	void backfillCorrectsAStrydZeroFallbackToNullInsteadOfLeavingItStale() {
		User athlete = newAthlete("stryd-zero-fallback@example.cc");
		Activity activity = newRunActivity(athlete, 60);
		activity.setAvgAirTemp(0.0);
		activity.setAvgHumidity(0);
		activity = activityRepository.save(activity);
		for (int t = 0; t < 60; t++) {
			Record record = new Record();
			record.setId(new RecordId(activity.getId(), activity.getStartDate().plusSeconds(t)));
			record.setActivity(activity);
			record.setT(t);
			record.setAirTemp(0.0);
			record.setHumidity(0);
			recordRepository.save(record);
		}

		Activity updated = derivedStatsRecomputeService.recomputeForActivity(activity.getId());

		assertThat(updated.getAvgAirTemp()).isNull();
		assertThat(updated.getAvgHumidity()).isNull();
	}

	@Test
	void backfillComputesRealAirTempAndHumidityForRunActivities() {
		User athlete = newAthlete("run-environment@example.cc");
		Activity activity = newRunActivity(athlete, 60);
		for (int t = 0; t < 60; t++) {
			Record record = new Record();
			record.setId(new RecordId(activity.getId(), activity.getStartDate().plusSeconds(t)));
			record.setActivity(activity);
			record.setT(t);
			record.setAirTemp(18.0 + (t % 3));
			record.setHumidity(55 + (t % 5));
			recordRepository.save(record);
		}

		Activity updated = derivedStatsRecomputeService.recomputeForActivity(activity.getId());

		assertThat(updated.getAvgAirTemp()).isCloseTo(19.0, org.assertj.core.data.Offset.offset(0.5));
		assertThat(updated.getAvgHumidity()).isCloseTo(57, org.assertj.core.data.Offset.offset(2));
	}

	private void addRecords(Activity activity, int seconds) {
		for (int t = 0; t < seconds; t++) {
			Record record = new Record();
			record.setId(new RecordId(activity.getId(), activity.getStartDate().plusSeconds(t)));
			record.setActivity(activity);
			record.setT(t);
			record.setPower(200);
			record.setCadence(85);
			record.setSpeed(8.0);
			record.setAltitude((double) (100 + (t % 10)));
			record.setHeartrate(160);
			recordRepository.save(record);
		}
	}

	@Test
	void recomputeForAthleteBackfillsStatsAcrossAllOfTheirActivities() {
		User athlete = newAthlete("bulk-backfill@example.cc");
		Activity first = newActivity(athlete, 3600);
		addRecords(first, 3600);
		Activity second = newActivity(athlete, 3600);
		addRecords(second, 3600);

		int updated = derivedStatsRecomputeService.recomputeForAthlete(athlete, null);

		assertThat(updated).isEqualTo(2);
		Activity reloadedFirst = activityRepository.findById(first.getId()).orElseThrow();
		Activity reloadedSecond = activityRepository.findById(second.getId()).orElseThrow();
		assertThat(reloadedFirst.getMaxPower()).isEqualTo(200);
		assertThat(reloadedSecond.getMaxPower()).isEqualTo(200);
		assertThat(reloadedFirst.getCalories()).isNotNull();
		assertThat(reloadedSecond.getCalories()).isNotNull();
	}

	@Test
	void recomputeForAthleteDoesNotTouchOtherAthletesActivities() {
		User athlete = newAthlete("bulk-mine@example.cc");
		User otherAthlete = newAthlete("bulk-other@example.cc");
		Activity otherActivity = newActivity(otherAthlete, 3600);

		int updated = derivedStatsRecomputeService.recomputeForAthlete(athlete, null);

		assertThat(updated).isZero();
		Activity reloaded = activityRepository.findById(otherActivity.getId()).orElseThrow();
		assertThat(reloaded.getMaxPower()).isNull();
	}

	@Test
	void recomputeForAthleteDoesNotCountActivitiesWithNothingToBackfill() {
		// No lthr on this athlete, unlike newAthlete()'s default, so TrimpCalculator also
		// has nothing to compute - otherwise trimp still resolves to a real 0.0 "zero time
		// in any zone" value (distinct from "nothing to backfill") even with zero records.
		User athlete = new User();
		athlete.setEmail("bulk-no-lthr@example.cc");
		athlete.setName("No LTHR");
		athlete.setPassword("irrelevant-for-this-test");
		athlete = userRepository.save(athlete);
		newActivity(athlete, 3600);

		int updated = derivedStatsRecomputeService.recomputeForAthlete(athlete, null);

		assertThat(updated).isZero();
	}

	@Test
	void recomputeForAthleteReportsProgressAfterEachActivity() {
		User athlete = newAthlete("bulk-progress@example.cc");
		addRecords(newActivity(athlete, 3600), 3600);
		addRecords(newActivity(athlete, 3600), 3600);
		addRecords(newActivity(athlete, 3600), 3600);

		List<int[]> calls = new ArrayList<>();
		derivedStatsRecomputeService.recomputeForAthlete(athlete, (current, total) -> calls.add(new int[] {current, total}));

		assertThat(calls).hasSize(3);
		assertThat(calls.get(0)).containsExactly(1, 3);
		assertThat(calls.get(2)).containsExactly(3, 3);
	}
}
