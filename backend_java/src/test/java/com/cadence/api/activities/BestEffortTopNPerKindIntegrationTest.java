package com.cadence.api.activities;

import static org.assertj.core.api.Assertions.assertThat;

import com.cadence.api.common.domain.Sport;
import com.cadence.api.support.IntegrationTest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Issue #597: running_pace defaults to unlimited retention (independent of the flat
 * bestEffortTopN), so an embedded split within a longer activity - which almost never ranks in
 * an athlete's all-time top N for that specific window - is no longer silently deleted the
 * moment it's computed. Power keeps the pre-existing trimmed-by-default behavior unless an
 * athlete opts into its own override.
 */
class BestEffortTopNPerKindIntegrationTest extends IntegrationTest {

	@Autowired
	private BestEffortComputeService bestEffortComputeService;
	@Autowired
	private BestEffortRepository bestEffortRepository;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private ActivityRepository activityRepository;

	// Outside every BestEffortWindows.TRIM_PERIOD_DAYS cutoff, so only the all-time ranking
	// (not a narrower period's own top-N) decides which rows survive trim.
	private static final long OUTSIDE_EVERY_PERIOD_DAYS = 1000;

	private User newAthlete(String email) {
		User user = new User();
		user.setEmail(email);
		user.setName("Athlete");
		user.setPassword("irrelevant-for-this-test");
		user.setFtp(200);
		return userRepository.save(user);
	}

	private BestEffort makeRow(User athlete, BestEffortKind kind, String window, double value, long daysAgo) {
		Activity activity = new Activity();
		activity.setAthlete(athlete);
		activity.setSport(kind == BestEffortKind.CYCLING_POWER ? Sport.BIKE : Sport.RUN);
		activity.setName("Fixture -" + daysAgo + "d");
		activity.setStartDate(Instant.now().minusSeconds(daysAgo * 86400));
		activity = activityRepository.save(activity);

		BestEffort effort = new BestEffort();
		effort.setAthlete(athlete);
		effort.setKind(kind);
		effort.setWindow(window);
		effort.setValue(value);
		effort.setUnit(kind == BestEffortKind.RUNNING_PACE ? "sec_per_km" : "watts");
		effort.setDate(LocalDate.now().minusDays(daysAgo));
		effort.setActivity(activity);
		return bestEffortRepository.save(effort);
	}

	private Activity newLongRun(User athlete, long daysAgo) {
		Activity activity = new Activity();
		activity.setAthlete(athlete);
		activity.setSport(Sport.RUN);
		activity.setName("Long run -" + daysAgo + "d");
		activity.setStartDate(Instant.now().minusSeconds(daysAgo * 86400));
		return activityRepository.save(activity);
	}

	private Activity newRide(User athlete, long daysAgo) {
		Activity activity = new Activity();
		activity.setAthlete(athlete);
		activity.setSport(Sport.BIKE);
		activity.setName("Mediocre ride");
		activity.setStartDate(Instant.now().minusSeconds(daysAgo * 86400));
		return activityRepository.save(activity);
	}

	@Test
	void anEmbeddedPaceSplitSurvivesByDefaultEvenWhenItIsNotAllTimeTopTen() {
		User athlete = newAthlete("pace-unlimited-default@example.cc");
		assertThat(athlete.getBestEffortTopN()).isEqualTo(10); // flat default, unchanged
		assertThat(athlete.getBestEffortTopNRunningPace()).isZero(); // pace's own default: unlimited

		// Ten standalone 1 km efforts, all faster than the embedded split below - these alone
		// would fill the old, shared top-10 under the pre-#597 behavior.
		for (int i = 0; i < 10; i++) {
			makeRow(athlete, BestEffortKind.RUNNING_PACE, "1km", 200.0 + i, OUTSIDE_EVERY_PERIOD_DAYS);
		}

		// An 11th, slower 1 km split - computed (not pre-inserted) through the real entry
		// point, same as a marathon's internal 1 km segment would be.
		Activity longRun = newLongRun(athlete, OUTSIDE_EVERY_PERIOD_DAYS);
		List<Integer> tSeries = new ArrayList<>();
		List<Double> distanceSeries = new ArrayList<>();
		int samples = 42200; // one sample per metre at a flat 400 sec/km pace
		for (int i = 0; i <= samples; i++) {
			tSeries.add((int) Math.round(i * 0.4));
			distanceSeries.add(i / 1000.0);
		}

		bestEffortComputeService.computeForActivity(
				longRun, athlete, BestEffortKind.RUNNING_PACE, Collections.emptyList(), Collections.emptyList(),
				tSeries, distanceSeries);

		List<BestEffort> rows = bestEffortRepository.findByAthleteIdAndKindAndWindowAndActivityPrimaryActivityIsNullOrderByValueAsc(
				athlete.getId(), BestEffortKind.RUNNING_PACE, "1km");
		assertThat(rows).hasSize(11);
		assertThat(rows).anySatisfy(row -> assertThat(row.getActivity().getId()).isEqualTo(longRun.getId()));
	}

	@Test
	void powerStaysTrimmedByDefaultUnlessOverridden() {
		User athlete = newAthlete("power-trimmed-default@example.cc");
		assertThat(athlete.getBestEffortTopNPower()).isNull(); // defers to the flat setting

		for (int i = 0; i < 10; i++) {
			makeRow(athlete, BestEffortKind.CYCLING_POWER, "5min", 300.0 - i, OUTSIDE_EVERY_PERIOD_DAYS);
		}
		Activity ride = newRide(athlete, OUTSIDE_EVERY_PERIOD_DAYS);
		List<Integer> powerSeries = Collections.nCopies(600, 150); // worse than every fixture row above

		bestEffortComputeService.computeForActivity(
				ride, athlete, BestEffortKind.CYCLING_POWER, powerSeries, Collections.emptyList(),
				Collections.emptyList(), Collections.emptyList());

		List<BestEffort> rows = bestEffortRepository.findByAthleteIdAndKindAndWindowAndActivityPrimaryActivityIsNullOrderByValueDesc(
				athlete.getId(), BestEffortKind.CYCLING_POWER, "5min");
		assertThat(rows).hasSize(10); // the flat topN=10 - the new, worse row was trimmed back out
		assertThat(rows).noneSatisfy(row -> assertThat(row.getActivity().getId()).isEqualTo(ride.getId()));
	}

	@Test
	void powerOverrideKeepsEverythingOnceSet() {
		User athlete = newAthlete("power-override-unlimited@example.cc");
		athlete.setBestEffortTopNPower(0);
		athlete = userRepository.save(athlete);

		for (int i = 0; i < 10; i++) {
			makeRow(athlete, BestEffortKind.CYCLING_POWER, "5min", 300.0 - i, OUTSIDE_EVERY_PERIOD_DAYS);
		}
		Activity ride = newRide(athlete, OUTSIDE_EVERY_PERIOD_DAYS);
		List<Integer> powerSeries = Collections.nCopies(600, 150);

		bestEffortComputeService.computeForActivity(
				ride, athlete, BestEffortKind.CYCLING_POWER, powerSeries, Collections.emptyList(),
				Collections.emptyList(), Collections.emptyList());

		List<BestEffort> rows = bestEffortRepository.findByAthleteIdAndKindAndWindowAndActivityPrimaryActivityIsNullOrderByValueDesc(
				athlete.getId(), BestEffortKind.CYCLING_POWER, "5min");
		assertThat(rows).hasSize(11);
	}
}
