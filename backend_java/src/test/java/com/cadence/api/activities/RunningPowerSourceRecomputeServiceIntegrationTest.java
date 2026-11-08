package com.cadence.api.activities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.cadence.api.athletes.RunningPowerSource;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.support.IntegrationTest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** Issue #503 (phase 2): mirrors backend's athletes/tests.py's RunningPowerSourceRecomputeJob
 * tests. Spies on the real {@code ActivityService} bean rather than building a full
 * threshold-ledger scenario - the cascade's own correctness is covered by {@code
 * ThresholdHistoryServiceIntegrationTest} and friends; this proves the bulk job calls it exactly
 * once per athlete, not once per swapped activity (see {@code
 * ActivityMergePostProcessingService#recomputeActivityLevelOnly}'s own Javadoc for why that
 * matters), same technique as {@code RecomputeAfterIngestTimeMarkIntegrationTest}. */
class RunningPowerSourceRecomputeServiceIntegrationTest extends IntegrationTest {

	private static final Instant START = Instant.parse("2026-06-01T18:00:00Z");

	@Autowired
	private RunningPowerSourceRecomputeService recomputeService;
	@Autowired
	private ActivitySourceService sourceService;
	@Autowired
	private ActivityRepository activityRepository;
	@Autowired
	private RecordRepository recordRepository;
	@Autowired
	private UserRepository userRepository;
	@MockitoSpyBean
	private ActivityService activityService;

	private User newAthlete(String email, RunningPowerSource runningPowerSource) {
		User user = new User();
		user.setEmail(email);
		user.setName("Athlete");
		user.setPassword("irrelevant-for-this-test");
		user.setRunningPowerSource(runningPowerSource);
		return userRepository.save(user);
	}

	private Activity newRunActivity(User athlete, String name, RunningPowerSource powerSource) {
		Activity activity = new Activity();
		activity.setAthlete(athlete);
		activity.setSport(Sport.RUN);
		activity.setName(name);
		activity.setStartDate(START);
		activity.setPowerSource(powerSource);
		return activityRepository.save(activity);
	}

	private void addRecords(Activity activity, int count, int power) {
		List<Record> records = new ArrayList<>();
		for (int t = 0; t < count; t++) {
			Record record = new Record();
			record.setId(new RecordId(activity.getId(), START.plusSeconds(t)));
			record.setActivity(activity);
			record.setT(t);
			record.setPower(power);
			records.add(record);
		}
		recordRepository.saveAll(records);
	}

	@Test
	void recomputeSwapsEveryMismatchedRunRecomputesTheAthleteOnceAndSkipsRunsWithNoStoredAlternate() {
		User athlete = newAthlete("rps-recompute-bulk@example.cc", RunningPowerSource.NATIVE);

		// Mismatched, with a stored native alternate to swap to.
		Activity mismatchedWithAlternate1 = newRunActivity(athlete, "Run A", RunningPowerSource.STRYD);
		addRecords(mismatchedWithAlternate1, 5, 200);
		sourceService.createParseTimeRunningPowerSource(mismatchedWithAlternate1, List.of(0, 1, 2, 3, 4),
				List.of(250, 251, 252, 253, 254), RunningPowerSource.NATIVE);

		Activity mismatchedWithAlternate2 = newRunActivity(athlete, "Run B", RunningPowerSource.STRYD);
		addRecords(mismatchedWithAlternate2, 5, 300);
		sourceService.createParseTimeRunningPowerSource(mismatchedWithAlternate2, List.of(0, 1, 2, 3, 4),
				List.of(350, 351, 352, 353, 354), RunningPowerSource.NATIVE);

		// Mismatched, but this file never had a native candidate to fall back on.
		Activity mismatchedWithoutAlternate = newRunActivity(athlete, "Run C", RunningPowerSource.STRYD);
		addRecords(mismatchedWithoutAlternate, 5, 400);

		// Already matches the preference - not a candidate at all.
		Activity alreadyMatching = newRunActivity(athlete, "Run D", RunningPowerSource.NATIVE);
		addRecords(alreadyMatching, 5, 500);

		RunningPowerSourceRecomputeService.Result result = recomputeService.recomputeForAthlete(athlete, (c, t) -> { });

		assertThat(result.swapped()).isEqualTo(2);
		assertThat(result.skipped()).isEqualTo(1);

		Activity reloaded1 = activityRepository.findById(mismatchedWithAlternate1.getId()).orElseThrow();
		assertThat(reloaded1.getPowerSource()).isEqualTo(RunningPowerSource.NATIVE);
		assertThat(recordRepository.findByActivityIdOrderByT(mismatchedWithAlternate1.getId()))
				.extracting(Record::getPower).containsExactly(250, 251, 252, 253, 254);

		Activity reloaded2 = activityRepository.findById(mismatchedWithAlternate2.getId()).orElseThrow();
		assertThat(reloaded2.getPowerSource()).isEqualTo(RunningPowerSource.NATIVE);
		assertThat(recordRepository.findByActivityIdOrderByT(mismatchedWithAlternate2.getId()))
				.extracting(Record::getPower).containsExactly(350, 351, 352, 353, 354);

		// The skipped run is completely untouched.
		Activity reloadedSkipped = activityRepository.findById(mismatchedWithoutAlternate.getId()).orElseThrow();
		assertThat(reloadedSkipped.getPowerSource()).isEqualTo(RunningPowerSource.STRYD);
		assertThat(recordRepository.findByActivityIdOrderByT(mismatchedWithoutAlternate.getId()))
				.allSatisfy(r -> assertThat(r.getPower()).isEqualTo(400));

		// The already-matching run was never touched either.
		Activity reloadedMatching = activityRepository.findById(alreadyMatching.getId()).orElseThrow();
		assertThat(reloadedMatching.getPowerSource()).isEqualTo(RunningPowerSource.NATIVE);
		assertThat(recordRepository.findByActivityIdOrderByT(alreadyMatching.getId()))
				.allSatisfy(r -> assertThat(r.getPower()).isEqualTo(500));

		// The athlete-wide cascade ran exactly once, not once per swapped activity.
		verify(activityService, times(1)).recomputeAfterDuplicateChange(argThat(a -> a.getId().equals(athlete.getId())));
	}

	@Test
	void recomputeIsANoOpWhenNothingIsMismatched() {
		User athlete = newAthlete("rps-recompute-noop@example.cc", RunningPowerSource.STRYD);
		Activity activity = newRunActivity(athlete, "Run", RunningPowerSource.STRYD);
		addRecords(activity, 5, 200);

		RunningPowerSourceRecomputeService.Result result = recomputeService.recomputeForAthlete(athlete, (c, t) -> { });

		assertThat(result.swapped()).isZero();
		assertThat(result.skipped()).isZero();
		verify(activityService, never()).recomputeAfterDuplicateChange(argThat(a -> a.getId().equals(athlete.getId())));
	}
}
