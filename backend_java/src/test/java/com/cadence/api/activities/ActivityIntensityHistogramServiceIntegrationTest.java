package com.cadence.api.activities;

import static org.assertj.core.api.Assertions.assertThat;

import com.cadence.api.athletes.DecayLoadService;
import com.cadence.api.athletes.RunningPowerSource;
import com.cadence.api.athletes.ThresholdField;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.support.IntegrationTest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The DB-touching half of ActivityIntensityHistogramService (rebuild/ensure, the lazy-backfill
 * path's own gating, cascade delete) and hook point #2 (DerivedStatsRecomputeService, the
 * "Recompute stats" action) - see docs/plans/threshold-decay.md section 5. Mirrors the Python
 * backend's activities/tests/test_intensity_histogram.py. Hook point #1 (ingest) is covered on
 * the Python side by a real upload end to end; the smoothing/tally logic both hooks share is
 * already covered by ActivityIntensityHistogramServiceTest and NormalizedPowerCalculatorTest, so
 * this class exercises the DB-level pattern once rather than duplicating that logic twice. */
class ActivityIntensityHistogramServiceIntegrationTest extends IntegrationTest {

	@Autowired
	private UserRepository userRepository;
	@Autowired
	private ActivityRepository activityRepository;
	@Autowired
	private RecordRepository recordRepository;
	@Autowired
	private ActivityIntensityHistogramRepository histogramRepository;
	@Autowired
	private ActivityIntensityHistogramService histogramService;
	@Autowired
	private DerivedStatsRecomputeService derivedStatsRecomputeService;
	@Autowired
	private DecayLoadService decayLoadService;

	private User newAthlete(String email) {
		User user = new User();
		user.setEmail(email);
		user.setName("Histogram Athlete");
		user.setPassword("irrelevant-for-this-test");
		user.setLthr(160);
		return userRepository.save(user);
	}

	private Activity activityWithRecords(User athlete, Sport sport, int power, int heartrate, int seconds) {
		Activity activity = new Activity();
		activity.setAthlete(athlete);
		activity.setSport(sport);
		activity.setName("Histogram " + sport);
		activity.setStartDate(Instant.parse("2026-02-01T08:00:00Z"));
		activity.setMovingTime(seconds);
		activityRepository.save(activity);
		for (int t = 0; t < seconds; t++) {
			Record r = new Record();
			r.setId(new RecordId(activity.getId(), activity.getStartDate().plusSeconds(t)));
			r.setActivity(activity);
			r.setT(t);
			r.setPower(power);
			r.setHeartrate(heartrate);
			recordRepository.save(r);
		}
		return activity;
	}

	@Test
	void rebuildBuildsAndSavesFromGivenSeries() {
		User athlete = newAthlete("histogram-rebuild@example.cc");
		Activity activity = activityWithRecords(athlete, Sport.BIKE, 200, 150, 60);

		ActivityIntensityHistogram histogram =
				histogramService.rebuildHistogram(activity, java.util.Collections.nCopies(60, 200), java.util.Collections.nCopies(60, 150));

		// 60 samples over a 30s window collapse to 60-30+1=31 rolling points; heart rate is
		// tallied raw.
		assertThat(histogram.getPower30s().values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(31);
		assertThat(histogram.getHeartrate()).containsExactly(java.util.Map.entry(150, 60));
	}

	@Test
	void ensureHistogramQueriesItsOwnRecordsWhenNoneStoredYet() {
		User athlete = newAthlete("histogram-ensure@example.cc");
		Activity activity = activityWithRecords(athlete, Sport.BIKE, 210, 155, 45);
		assertThat(histogramRepository.findById(activity.getId())).isEmpty();

		ActivityIntensityHistogram histogram = histogramService.ensureHistogram(activity);

		assertThat(histogram.getPower30s().values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(45 - 30 + 1);
		assertThat(histogram.getHeartrate()).containsExactly(java.util.Map.entry(155, 45));
		assertThat(histogramRepository.findById(activity.getId())).isPresent();
	}

	@Test
	void ensureHistogramReusesTheStoredRowRatherThanRebuilding() {
		User athlete = newAthlete("histogram-reuse@example.cc");
		Activity activity = activityWithRecords(athlete, Sport.BIKE, 190, 150, 10);
		ActivityIntensityHistogram first = histogramService.ensureHistogram(activity);
		first.setPower30s(Map.of(1, 999));
		histogramRepository.save(first);

		ActivityIntensityHistogram second = histogramService.ensureHistogram(activity);
		assertThat(second.getPower30s()).containsExactly(java.util.Map.entry(1, 999));
	}

	@Test
	void selfQueriedRunActivityIsGatedByRunningPowerPreference() {
		User athlete = newAthlete("histogram-gate@example.cc");
		athlete.setRunningPowerSource(RunningPowerSource.NATIVE);
		userRepository.save(athlete);
		Activity activity = activityWithRecords(athlete, Sport.RUN, 300, 150, 40);
		activity.setPowerSource(RunningPowerSource.STRYD);
		activityRepository.save(activity);

		ActivityIntensityHistogram histogram = histogramService.ensureHistogram(activity);
		assertThat(histogram.getPower30s()).isNull();
	}

	@Test
	void deletingTheActivityCascades() {
		User athlete = newAthlete("histogram-cascade@example.cc");
		Activity activity = activityWithRecords(athlete, Sport.BIKE, 180, 150, 5);
		histogramService.ensureHistogram(activity);
		assertThat(histogramRepository.findById(activity.getId())).isPresent();

		activityRepository.delete(activity);
		assertThat(histogramRepository.findById(activity.getId())).isEmpty();
	}

	@Test
	void recomputeStatsBuildsAHistogramWithSaneTotals() {
		User athlete = newAthlete("histogram-recompute@example.cc");
		Activity activity = activityWithRecords(athlete, Sport.BIKE, 210, 145, 120);
		assertThat(histogramRepository.findById(activity.getId())).isEmpty();

		derivedStatsRecomputeService.recomputeForActivity(activity.getId());

		ActivityIntensityHistogram histogram = histogramRepository.findById(activity.getId()).orElseThrow();
		assertThat(histogram.getPower30s().values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(120 - 30 + 1);
		assertThat(histogram.getHeartrate().values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(120);
	}

	@Test
	void loadByDayAggregatesAcrossActivitiesAndSmoothsTheResult() {
		User athlete = newAthlete("histogram-load@example.cc");
		Activity day1 = activityWithRecords(athlete, Sport.BIKE, 220, 150, 600);
		day1.setStartDate(LocalDate.of(2026, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(8 * 3600));
		activityRepository.save(day1);
		Activity day2 = activityWithRecords(athlete, Sport.BIKE, 220, 150, 600);
		day2.setStartDate(LocalDate.of(2026, 1, 2).atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(8 * 3600));
		activityRepository.save(day2);

		Map<LocalDate, Double> series = decayLoadService.loadByDayForAthlete(
				athlete, ThresholdField.FTP, 260.0, 160.0, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 3));

		assertThat(series.keySet()).containsExactly(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 2));
		assertThat(series.get(LocalDate.of(2026, 1, 2))).isGreaterThan(series.get(LocalDate.of(2026, 1, 1)));
	}
}
