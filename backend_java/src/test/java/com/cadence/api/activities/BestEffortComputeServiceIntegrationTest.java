package com.cadence.api.activities;

import static org.assertj.core.api.Assertions.assertThat;

import com.cadence.api.common.domain.Sport;
import com.cadence.api.support.IntegrationTest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * trimToTop keeps a row if it's a top-N record within ANY tracked period
 * (BestEffortWindows.TRIM_PERIOD_DAYS, or all-time), not just the all-time top-N. Mirrors the
 * Python backend's uploads/tests/test_best_efforts_and_matching.py::BestEffortTrimPeriodTests.
 */
class BestEffortComputeServiceIntegrationTest extends IntegrationTest {

	@Autowired
	private BestEffortComputeService bestEffortComputeService;

	@Autowired
	private BestEffortRepository bestEffortRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private ActivityRepository activityRepository;

	private static final int TOP_N = 2;

	private User newAthlete(String email) {
		User user = new User();
		user.setEmail(email);
		user.setName("Trim Athlete");
		user.setPassword("irrelevant-for-this-test");
		user.setBestEffortTopN(TOP_N);
		return userRepository.save(user);
	}

	private BestEffort makeEffort(User athlete, double value, long daysAgo, String window) {
		Activity activity = new Activity();
		activity.setAthlete(athlete);
		activity.setSport(Sport.RUN);
		activity.setName("Run -" + daysAgo + "d");
		activity.setStartDate(Instant.now().minusSeconds(daysAgo * 86400));
		activity = activityRepository.save(activity);

		BestEffort effort = new BestEffort();
		effort.setAthlete(athlete);
		effort.setKind(BestEffortKind.RUNNING_PACE);
		effort.setWindow(window);
		effort.setValue(value);
		effort.setUnit("sec_per_km");
		effort.setDate(LocalDate.now().minusDays(daysAgo));
		effort.setActivity(activity);
		return bestEffortRepository.save(effort);
	}

	private List<Long> survivorIds(User athlete, String window) {
		return bestEffortRepository.findByAthleteIdAndKindAndWindowOrderByValueAsc(
						athlete.getId(), BestEffortKind.RUNNING_PACE, window)
				.stream().map(BestEffort::getId).collect(Collectors.toList());
	}

	@Test
	void recentEffortSurvivesEvenWhenNotAllTimeTopN() {
		User athlete = newAthlete("trim-recent@example.cc");
		// Two very fast, very old efforts fill the all-time top-2 (lower value = better pace).
		BestEffort old1 = makeEffort(athlete, 200.0, 1000, "10km");
		BestEffort old2 = makeEffort(athlete, 210.0, 900, "10km");
		// 3rd all-time, but the only effort in the last 28 days - should survive on that basis.
		BestEffort recent = makeEffort(athlete, 280.0, 10, "10km");

		bestEffortComputeService.trim(athlete.getId(), BestEffortKind.RUNNING_PACE, "10km", true, TOP_N);

		assertThat(survivorIds(athlete, "10km")).containsExactlyInAnyOrder(old1.getId(), old2.getId(), recent.getId());
	}

	@Test
	void rowDeletedOnceItLosesEveryPeriod() {
		User athlete = newAthlete("trim-lose@example.cc");
		makeEffort(athlete, 200.0, 1000, "10km");
		makeEffort(athlete, 210.0, 900, "10km");
		BestEffort recent = makeEffort(athlete, 280.0, 10, "10km");
		bestEffortComputeService.trim(athlete.getId(), BestEffortKind.RUNNING_PACE, "10km", true, TOP_N);
		assertThat(survivorIds(athlete, "10km")).contains(recent.getId());

		// Two faster, similarly-recent efforts now fill every period's top-2 ahead of `recent` -
		// it's no longer a record in the 28-day window, and every wider period it also belongs
		// to (90/112/365/all) prefers these two over it as well.
		makeEffort(athlete, 150.0, 5, "10km");
		makeEffort(athlete, 160.0, 4, "10km");
		bestEffortComputeService.trim(athlete.getId(), BestEffortKind.RUNNING_PACE, "10km", true, TOP_N);

		assertThat(survivorIds(athlete, "10km")).doesNotContain(recent.getId());
	}

	@Test
	void trimBoundRespectsTopNTimesPeriodCount() {
		User athlete = newAthlete("trim-bound@example.cc");
		// One pair of rows per tracked period, deliberately slower the more recent the band - so
		// each period's own top-2 are exactly that band's two rows, none of which intrude on a
		// narrower (more recent) period's top-2.
		long maxCutoff = BestEffortWindows.TRIM_PERIOD_DAYS.stream().mapToLong(Long::valueOf).max().orElseThrow();
		List<Long> bandsOldestFirst = List.of(
				maxCutoff + 35,
				365L, 112L, 90L, 28L);
		List<Long> expectedSurvivors = new java.util.ArrayList<>();
		for (int bandIndex = 0; bandIndex < bandsOldestFirst.size(); bandIndex++) {
			long daysAgo = bandsOldestFirst.get(bandIndex);
			double baseValue = bandIndex * 10.0;
			expectedSurvivors.add(makeEffort(athlete, baseValue, daysAgo - 5, "10km").getId());
			expectedSurvivors.add(makeEffort(athlete, baseValue + 1, daysAgo - 4, "10km").getId());
		}
		// Not fast enough to win any period - proves this is the one that gets dropped, rather
		// than the bound being exceeded.
		BestEffort loser = makeEffort(athlete, 999.0, 3, "10km");

		bestEffortComputeService.trim(athlete.getId(), BestEffortKind.RUNNING_PACE, "10km", true, TOP_N);

		List<Long> survivors = survivorIds(athlete, "10km");
		assertThat(survivors).hasSize(TOP_N * (BestEffortWindows.TRIM_PERIOD_DAYS.size() + 1));
		assertThat(survivors).containsExactlyInAnyOrderElementsOf(expectedSurvivors);
		assertThat(survivors).doesNotContain(loser.getId());
	}
}
