package com.cadence.api.activities;

import static org.assertj.core.api.Assertions.assertThat;

import com.cadence.api.common.domain.Sport;
import com.cadence.api.support.IntegrationTest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import java.time.Instant;
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
}
