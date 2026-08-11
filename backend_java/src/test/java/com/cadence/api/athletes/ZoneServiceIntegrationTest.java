package com.cadence.api.athletes;

import static org.assertj.core.api.Assertions.assertThat;

import com.cadence.api.activities.Activity;
import com.cadence.api.activities.ActivityRepository;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.support.IntegrationTest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** referenceFor's activity-scoping: bike_power/run_power/pace read the activity's own threshold
 * snapshot when given one, instead of the athlete's current (possibly since-changed) profile. */
class ZoneServiceIntegrationTest extends IntegrationTest {

	@Autowired
	private ZoneService zoneService;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private ActivityRepository activityRepository;

	private User newAthlete(String email) {
		User user = new User();
		user.setEmail(email);
		user.setName("Test Athlete");
		user.setPassword("irrelevant-for-this-test");
		user.setFtp(250);
		user.setLthr(160);
		user.setThresholdPace("4:00");
		return userRepository.save(user);
	}

	private Activity newActivity(User athlete, Sport sport) {
		Activity activity = new Activity();
		activity.setAthlete(athlete);
		activity.setSport(sport);
		activity.setName("Old activity");
		activity.setStartDate(Instant.parse("2026-01-01T07:00:00Z"));
		return activityRepository.save(activity);
	}

	@Test
	void withoutActivityReadsTheAthletesLiveProfile() {
		User athlete = newAthlete("zone-reference-live@example.cc");
		assertThat(zoneService.referenceFor(athlete, ZoneType.BIKE_POWER)).isEqualTo(250.0);
		assertThat(zoneService.referenceFor(athlete, ZoneType.PACE)).isEqualTo(240.0); // "4:00" -> 240s
	}

	@Test
	void withActivityReadsThatActivitysSnapshot() {
		User athlete = newAthlete("zone-reference-snapshot@example.cc");
		Activity activity = newActivity(athlete, Sport.BIKE);
		activity.setFtpSnapshot(200);
		activity = activityRepository.save(activity);

		assertThat(zoneService.referenceFor(athlete, ZoneType.BIKE_POWER, activity)).isEqualTo(200.0);
		// The athlete's live profile still says 250 - proves the snapshot, not the live value, won.
		assertThat(zoneService.referenceFor(athlete, ZoneType.BIKE_POWER)).isEqualTo(250.0);
	}

	@Test
	void paceSnapshotIsParsedFromMmssSameAsTheLiveField() {
		User athlete = newAthlete("zone-reference-pace@example.cc");
		Activity activity = newActivity(athlete, Sport.RUN);
		activity.setThresholdPaceSnapshot("4:30");
		activity = activityRepository.save(activity);

		assertThat(zoneService.referenceFor(athlete, ZoneType.PACE, activity)).isEqualTo(270.0);
	}

	@Test
	void heartRateIgnoresActivityAndAlwaysReadsLive() {
		User athlete = newAthlete("zone-reference-hr@example.cc");
		Activity activity = newActivity(athlete, Sport.BIKE);
		activity.setFtpSnapshot(200);
		activity = activityRepository.save(activity);

		assertThat(zoneService.referenceFor(athlete, ZoneType.HEART_RATE, activity)).isEqualTo(160.0);
	}

	@Test
	void aNullSnapshotReturnsNullRatherThanFallingBackToTheLiveProfile() {
		// An activity with no snapshot (e.g. pre-dating this feature and never backfilled)
		// should read as "unknown," not silently fall back to the athlete's current FTP - that
		// fallback-to-live behavior is exactly what activity-scoping exists to avoid.
		User athlete = newAthlete("zone-reference-null@example.cc");
		Activity activity = newActivity(athlete, Sport.BIKE);

		assertThat(zoneService.referenceFor(athlete, ZoneType.BIKE_POWER, activity)).isNull();
	}

	@Test
	void nullActivityFallsBackToTheTwoArgOverload() {
		User athlete = newAthlete("zone-reference-null-activity@example.cc");
		assertThat(zoneService.referenceFor(athlete, ZoneType.BIKE_POWER, null)).isEqualTo(250.0);
	}
}
