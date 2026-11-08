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
	private RecordRepository recordRepository;

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

	/** See ActivityOverlapDetectionServiceIntegrationTest's own copy of this helper. */
	private void addCorrelatedCadence(Activity activity, Instant start, int count, int phaseOffset) {
		List<Record> records = new ArrayList<>();
		for (int t = 0; t < count; t++) {
			Record record = new Record();
			record.setId(new RecordId(activity.getId(), start.plusSeconds(t)));
			record.setActivity(activity);
			record.setT(t);
			record.setCadence((int) Math.round(80 + 5 * Math.sin((t + phaseOffset) / 30.0)));
			records.add(record);
		}
		recordRepository.saveAll(records);
	}

	@Test
	void applyModeWithMergeActionMergesAndHidesAHighScoringPair() {
		User athlete = newUser("overlap-backfill-apply-merge@example.cc");
		Instant start = Instant.parse("2026-06-01T18:00:00Z");
		Activity zwift = newActivity(athlete, "Zwift", start);
		Activity garmin = newActivity(athlete, "Garmin", start.plusSeconds(5));
		addCorrelatedCadence(zwift, start, 120, 0);
		addCorrelatedCadence(garmin, start.plusSeconds(5), 120, 5);

		// Scoped to this athlete specifically - the no-filter tests above rely on MARK/FLAG never
		// throwing (see ActivityOverlapDetectionService#merge's own validation-safety-net note),
		// but there's no reason for this test's own assertions to depend on every other athlete
		// already sitting in the shared integration-test database resolving cleanly too.
		runner.run(new DefaultApplicationArguments(
				"--backfill-overlaps", "--mode=apply", "--action=merge", "--athlete=" + athlete.getId()));

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
