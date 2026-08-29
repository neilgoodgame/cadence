package com.cadence.api.activities;

import static org.assertj.core.api.Assertions.assertThat;

import com.cadence.api.athletes.RunningPowerSource;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.support.IntegrationTest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** A run activity whose powerSource no longer matches the athlete's current
 * runningPowerSource preference is excluded from RUNNING_POWER best efforts entirely - see
 * Activity.matchesRunningPowerPreference. */
class RunningPowerBestEffortSourceGateTest extends IntegrationTest {

	@Autowired
	private BestEffortComputeService bestEffortComputeService;
	@Autowired
	private BestEffortRepository bestEffortRepository;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private ActivityRepository activityRepository;

	private User newAthlete(String email) {
		User user = new User();
		user.setEmail(email);
		user.setName("Athlete");
		user.setPassword("irrelevant-for-this-test");
		user.setCriticalRunPower(1);
		user.setRunningPowerSource(RunningPowerSource.STRYD);
		return userRepository.save(user);
	}

	private Activity newRun(User athlete, RunningPowerSource powerSource) {
		Activity activity = new Activity();
		activity.setAthlete(athlete);
		activity.setSport(Sport.RUN);
		activity.setName("Run");
		activity.setStartDate(Instant.parse("2026-06-10T07:00:00Z"));
		activity.setPowerSource(powerSource);
		return activityRepository.save(activity);
	}

	private List<Integer> powerSeries() {
		return Collections.nCopies(60, 300);
	}

	@Test
	void mismatchedSourceProducesNoRunningPowerBestEffort() {
		User athlete = newAthlete("be-power-source-mismatch@example.cc");
		Activity activity = newRun(athlete, RunningPowerSource.NATIVE);

		bestEffortComputeService.computeForActivity(
				activity, athlete, powerSeries(), List.of(), List.of(), List.of());

		assertThat(bestEffortRepository.findByAthleteIdAndKindOrderByWindowAscValueDesc(
				athlete.getId(), BestEffortKind.RUNNING_POWER)).isEmpty();
	}

	@Test
	void matchingSourceProducesARunningPowerBestEffort() {
		User athlete = newAthlete("be-power-source-match@example.cc");
		Activity activity = newRun(athlete, RunningPowerSource.STRYD);

		bestEffortComputeService.computeForActivity(
				activity, athlete, powerSeries(), List.of(), List.of(), List.of());

		assertThat(bestEffortRepository.findByAthleteIdAndKindOrderByWindowAscValueDesc(
				athlete.getId(), BestEffortKind.RUNNING_POWER)).isNotEmpty();
	}

	@Test
	void untaggedSourceIsTrustedAsBefore() {
		// A pre-feature activity (powerSource=null) - not newly excluded by this preference.
		User athlete = newAthlete("be-power-source-untagged@example.cc");
		Activity activity = newRun(athlete, null);

		bestEffortComputeService.computeForActivity(
				activity, athlete, powerSeries(), List.of(), List.of(), List.of());

		assertThat(bestEffortRepository.findByAthleteIdAndKindOrderByWindowAscValueDesc(
				athlete.getId(), BestEffortKind.RUNNING_POWER)).isNotEmpty();
	}
}
