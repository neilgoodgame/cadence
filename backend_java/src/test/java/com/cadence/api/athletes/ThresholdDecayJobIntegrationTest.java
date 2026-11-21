package com.cadence.api.athletes;

import static org.assertj.core.api.Assertions.assertThat;

import com.cadence.api.activities.Activity;
import com.cadence.api.activities.ActivityRepository;
import com.cadence.api.activities.Record;
import com.cadence.api.activities.RecordId;
import com.cadence.api.activities.RecordRepository;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.support.IntegrationTest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The daily companion to the four real-time triggers (issue #548, split out of #485) - only
 * matters for an athlete who goes quiet on one sport but not another, since
 * ingest/manual-edit/refresh/rebuild already self-correct a stale ledger whenever any of them
 * next fires for that field. Mirrors the Python backend's
 * athletes/test_threshold_decay_recompute.py::ApplyThresholdDecayJobTests. */
class ThresholdDecayJobIntegrationTest extends IntegrationTest {

	@Autowired
	private ThresholdDecayJob thresholdDecayJob;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private ActivityRepository activityRepository;
	@Autowired
	private RecordRepository recordRepository;
	@Autowired
	private ThresholdHistoryService thresholdHistoryService;
	@Autowired
	private ThresholdDecayRecomputeService recomputeService;
	@Autowired
	private ThresholdHistoryRepository thresholdHistoryRepository;

	private User newDecayAthlete(String email) {
		User user = new User();
		user.setEmail(email);
		user.setName("Decay Job Athlete");
		user.setPassword("irrelevant-for-this-test");
		user.setThresholdDecayEnabled(true);
		user.setThresholdWindowDays(10);
		user.setThresholdSanityPct(50);
		user.setThresholdDecayTauDays(5);
		user.setThresholdDecayMinPct(3);
		user.setThresholdDecayMaxPct(12);
		return userRepository.save(user);
	}

	private Activity newPowerActivity(User owner, Instant startDate, int power, int durationSeconds) {
		Activity activity = new Activity();
		activity.setAthlete(owner);
		activity.setSport(Sport.BIKE);
		activity.setName("Ride");
		activity.setStartDate(startDate);
		activity.setMovingTime(durationSeconds);
		activity = activityRepository.save(activity);
		for (int t = 0; t < durationSeconds; t++) {
			Record record = new Record();
			record.setId(new RecordId(activity.getId(), startDate.plusSeconds(t)));
			record.setActivity(activity);
			record.setT(t);
			record.setPower(power);
			recordRepository.save(record);
		}
		return activity;
	}

	@Test
	void skipsAnAthleteWhoseAnchorHasNotExpiredYet() {
		User athlete = newDecayAthlete("decay-job-fresh@example.cc");
		Activity activity = newPowerActivity(athlete, Instant.now(), 260, 1200);
		thresholdHistoryService.recomputeForActivity(activity);

		thresholdDecayJob.applyThresholdDecay();

		List<ThresholdHistory> decayed = thresholdHistoryRepository
				.findByAthleteIdAndFieldAndKindAndCorrectionOnlyFalseOrderByEffectiveFromAsc(athlete.getId(),
						ThresholdField.FTP, ThresholdHistoryKind.DECAYED);
		assertThat(decayed).isEmpty();
	}

	@Test
	void appliesDecayWhenTheAnchorHasExpiredForAQuietSport() {
		User athlete = newDecayAthlete("decay-job-stale@example.cc");
		Instant start = LocalDate.of(2026, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant();
		Activity activity = newPowerActivity(athlete, start, 260, 1200);
		// A genuinely stale ledger: the athlete's last bike activity was 2026-01-01, and at that
		// time the ingest trigger resimulated only through 2026-01-01 (today then) - not through
		// the real today. recomputeForActivity would resimulate through the real today and
		// pre-decay the anchor to its floor, making applyThresholdDecay a no-op; resimulating
		// only through the activity's own date reproduces the ledger state a quiet athlete has.
		// 9-arg overload (the only @Transactional one) - the 4-arg convenience form reaches it via
		// self-invocation and would run non-transactionally here, where the test has no enclosing tx.
		recomputeService.resimulateSuffix(athlete, ThresholdField.FTP, LocalDate.of(2026, 1, 1),
				LocalDate.of(2026, 1, 1), null, null, true, false, false);

		thresholdDecayJob.applyThresholdDecay();

		List<ThresholdHistory> decayed = thresholdHistoryRepository
				.findByAthleteIdAndFieldAndKindAndCorrectionOnlyFalseOrderByEffectiveFromAsc(athlete.getId(),
						ThresholdField.FTP, ThresholdHistoryKind.DECAYED);
		assertThat(decayed).isNotEmpty();
		// The job itself did fresh work: the newest decayed row is current as of today (not a
		// leftover from seeding, which produced no decayed rows at all).
		assertThat(decayed.get(decayed.size() - 1).getCurrentFrom()).isEqualTo(LocalDate.now());
		User reloaded = userRepository.findById(athlete.getId()).orElseThrow();
		assertThat(reloaded.getFtp()).isLessThan(260);
	}

	@Test
	void skipsADecayDisabledAthleteEvenWithAnExpiredAnchor() {
		User athlete = new User();
		athlete.setEmail("decay-job-disabled@example.cc");
		athlete.setName("Decay Disabled");
		athlete.setPassword("irrelevant-for-this-test");
		athlete = userRepository.save(athlete);
		Instant start = LocalDate.of(2026, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant();
		Activity activity = newPowerActivity(athlete, start, 260, 1200);
		thresholdHistoryService.recomputeForActivity(activity);

		thresholdDecayJob.applyThresholdDecay();

		List<ThresholdHistory> decayed = thresholdHistoryRepository
				.findByAthleteIdAndFieldAndKindAndCorrectionOnlyFalseOrderByEffectiveFromAsc(athlete.getId(),
						ThresholdField.FTP, ThresholdHistoryKind.DECAYED);
		assertThat(decayed).isEmpty();
	}

	@Test
	void skipsAFieldWithNoLedgerRowsAtAll() {
		User athlete = newDecayAthlete("decay-job-no-ledger@example.cc");

		thresholdDecayJob.applyThresholdDecay(); // must not throw

		List<ThresholdHistory> rows = thresholdHistoryRepository.findByAthleteIdOrderByEffectiveFrom(athlete.getId());
		assertThat(rows).isEmpty();
	}
}
