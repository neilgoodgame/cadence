package com.cadence.api.activities;

import static org.assertj.core.api.Assertions.assertThat;

import com.cadence.api.common.domain.Sport;
import com.cadence.api.support.IntegrationTest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;

/** Issue #498's backfill command for pairs that already existed before on-ingest detection
 * shipped - see the runner's own Javadoc. The primary-selection/mark/flag logic itself is
 * covered directly (and far more cheaply) by ActivityOverlapDetectionServiceIntegrationTest;
 * this only exercises the runner's own flag parsing, report/apply wiring, and athlete scoping. */
class OverlapBackfillRunnerIntegrationTest extends IntegrationTest {

	@Autowired
	private OverlapBackfillRunner runner;

	@Autowired
	private ActivityRepository activityRepository;

	@Autowired
	private UserRepository userRepository;

	private User newUser(String email) {
		User user = new User();
		user.setEmail(email);
		user.setName("Test User " + email);
		user.setPassword("irrelevant-for-this-test");
		return userRepository.save(user);
	}

	private Activity newActivity(User athlete, String device, Instant startDate) {
		Activity activity = new Activity();
		activity.setAthlete(athlete);
		activity.setSport(Sport.BIKE);
		activity.setName("Bike on " + startDate);
		activity.setDevice(device);
		activity.setStartDate(startDate);
		activity.setElapsedSeconds(3600);
		activity.setMovingTime(3600);
		return activityRepository.save(activity);
	}

	@Test
	void withNoBackfillFlagDoesAbsolutelyNothing() {
		User athlete = newUser("overlap-backfill-noop@example.cc");
		Instant start = Instant.parse("2026-06-01T18:00:00Z");
		newActivity(athlete, "Zwift", start);
		Activity garmin = newActivity(athlete, "Garmin", start.plusSeconds(5));

		runner.run(new DefaultApplicationArguments());

		assertThat(activityRepository.findById(garmin.getId()).orElseThrow().getPrimaryActivity()).isNull();
	}

	@Test
	void reportModeIsTheDefaultAndChangesNothing() {
		User athlete = newUser("overlap-backfill-report@example.cc");
		Instant start = Instant.parse("2026-06-01T18:00:00Z");
		newActivity(athlete, "Zwift", start);
		Activity garmin = newActivity(athlete, "Garmin", start.plusSeconds(5));

		runner.run(new DefaultApplicationArguments("--backfill-overlaps"));

		assertThat(activityRepository.findById(garmin.getId()).orElseThrow().getPrimaryActivity()).isNull();
	}

	@Test
	void applyModeDefaultsToMarkAndLinksTheGarminCopyToTheZwiftCopy() {
		User athlete = newUser("overlap-backfill-apply-mark@example.cc");
		Instant start = Instant.parse("2026-06-01T18:00:00Z");
		Activity zwift = newActivity(athlete, "Zwift", start);
		Activity garmin = newActivity(athlete, "Garmin", start.plusSeconds(5));

		runner.run(new DefaultApplicationArguments("--backfill-overlaps", "--mode=apply"));

		Activity reloadedGarmin = activityRepository.findById(garmin.getId()).orElseThrow();
		assertThat(reloadedGarmin.getPrimaryActivity()).isNotNull();
		assertThat(reloadedGarmin.getPrimaryActivity().getId()).isEqualTo(zwift.getId());
	}

	@Test
	void applyModeWithFlagActionLeavesBothActivitiesUntouched() {
		User athlete = newUser("overlap-backfill-apply-flag@example.cc");
		Instant start = Instant.parse("2026-06-01T18:00:00Z");
		newActivity(athlete, "Zwift", start);
		Activity garmin = newActivity(athlete, "Garmin", start.plusSeconds(5));

		runner.run(new DefaultApplicationArguments("--backfill-overlaps", "--mode=apply", "--action=flag"));

		assertThat(activityRepository.findById(garmin.getId()).orElseThrow().getPrimaryActivity()).isNull();
	}

	@Test
	void athleteFilterOnlyTouchesThatAthletesPairs() {
		User athlete = newUser("overlap-backfill-scope-a@example.cc");
		User otherAthlete = newUser("overlap-backfill-scope-b@example.cc");
		Instant start = Instant.parse("2026-06-01T18:00:00Z");
		newActivity(athlete, "Zwift", start);
		Activity garmin = newActivity(athlete, "Garmin", start.plusSeconds(5));
		Activity otherZwift = newActivity(otherAthlete, "Zwift", start);
		Activity otherGarmin = newActivity(otherAthlete, "Garmin", start.plusSeconds(5));

		runner.run(new DefaultApplicationArguments(
				"--backfill-overlaps", "--mode=apply", "--athlete=" + otherAthlete.getId()));

		assertThat(activityRepository.findById(garmin.getId()).orElseThrow().getPrimaryActivity()).isNull();
		Activity reloadedOtherGarmin = activityRepository.findById(otherGarmin.getId()).orElseThrow();
		assertThat(reloadedOtherGarmin.getPrimaryActivity()).isNotNull();
		assertThat(reloadedOtherGarmin.getPrimaryActivity().getId()).isEqualTo(otherZwift.getId());
	}
}
