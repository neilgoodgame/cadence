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
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** recordManualValue - a manually-entered threshold (see AthleteService.updateProfile) is
 * trusted unconditionally and functions as an initial value (or a correction) exactly like any
 * other ledger entry from that point on. */
class ThresholdHistoryServiceIntegrationTest extends IntegrationTest {

	@Autowired
	private ThresholdHistoryService thresholdHistoryService;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private ActivityRepository activityRepository;
	@Autowired
	private RecordRepository recordRepository;
	@Autowired
	private ThresholdHistoryRepository thresholdHistoryRepository;
	@Autowired
	private ZoneService zoneService;

	private User newAthlete(String email) {
		User user = new User();
		user.setEmail(email);
		user.setName("Test Athlete");
		user.setPassword("irrelevant-for-this-test");
		return userRepository.save(user);
	}

	// Mirrors ThresholdHistoryCalculatorTest's own helper - a local copy rather than a
	// cross-test-class import, matching this codebase's existing convention (see
	// ThresholdHistoryCalculator.mmssToSeconds's comment for the same reasoning).
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
	void seedsTheInitialValueWhenNoEntryExists() {
		User athlete = newAthlete("manual-threshold-seed@example.cc");

		boolean changed = thresholdHistoryService.recordManualValue(athlete, ThresholdField.FTP, 250, null);

		assertThat(changed).isTrue();
		ThresholdHistory entry = thresholdHistoryRepository
				.findFirstByAthleteIdAndFieldOrderByEffectiveFromDescIdDesc(athlete.getId(), ThresholdField.FTP).orElseThrow();
		assertThat(entry.getValueNumeric()).isEqualTo(250);
		assertThat(entry.getSourceActivity()).isNull();
		assertThat(entry.getEffectiveFrom()).isEqualTo(LocalDate.now());
	}

	@Test
	void noOpWhenTheValueMatchesTheLatestEntry() {
		User athlete = newAthlete("manual-threshold-noop@example.cc");
		thresholdHistoryService.recordManualValue(athlete, ThresholdField.FTP, 250, null);

		boolean changed = thresholdHistoryService.recordManualValue(athlete, ThresholdField.FTP, 250, null);

		assertThat(changed).isFalse();
		assertThat(thresholdHistoryRepository
				.findByAthleteIdAndFieldOrderByEffectiveFromDescIdDesc(athlete.getId(), ThresholdField.FTP)).hasSize(1);
	}

	@Test
	void recordsACorrectionEvenThoughItIsADecrease() {
		// No sanity-band check for a manual entry - a human directly declaring a number is
		// trusted, unlike an automatically-detected candidate that needs outlier protection.
		User athlete = newAthlete("manual-threshold-decrease@example.cc");
		thresholdHistoryService.recordManualValue(athlete, ThresholdField.FTP, 300, null);

		boolean changed = thresholdHistoryService.recordManualValue(athlete, ThresholdField.FTP, 180, null);

		assertThat(changed).isTrue();
		List<ThresholdHistory> entries = thresholdHistoryRepository
				.findByAthleteIdAndFieldOrderByEffectiveFromDescIdDesc(athlete.getId(), ThresholdField.FTP);
		assertThat(entries).hasSize(2);
		assertThat(entries.get(0).getValueNumeric()).isEqualTo(180);
	}

	@Test
	void thresholdPaceStoresTheMmssString() {
		User athlete = newAthlete("manual-threshold-pace@example.cc");

		boolean changed = thresholdHistoryService.recordManualValue(athlete, ThresholdField.THRESHOLD_PACE, null, "4:15");

		assertThat(changed).isTrue();
		ThresholdHistory entry = thresholdHistoryRepository
				.findFirstByAthleteIdAndFieldOrderByEffectiveFromDescIdDesc(athlete.getId(), ThresholdField.THRESHOLD_PACE).orElseThrow();
		assertThat(entry.getValuePace()).isEqualTo("4:15");
		assertThat(entry.getValueNumeric()).isNull();
	}

	@Test
	void aManualEntryBecomesTheActivityScopedReferenceGoingForward() {
		User athlete = newAthlete("manual-threshold-reference@example.cc");
		thresholdHistoryService.recordManualValue(athlete, ThresholdField.FTP, 250, null);
		Activity activity = new Activity();
		activity.setAthlete(athlete);
		activity.setSport(Sport.BIKE);
		activity.setName("Ride");
		activity.setStartDate(Instant.now().plusSeconds(3600));
		activity = activityRepository.save(activity);

		assertThat(zoneService.referenceFor(athlete, ZoneType.BIKE_POWER, activity)).isEqualTo(250.0);
	}

	// Regression test for a real bug found live: a manually-entered value effective from today
	// permanently outranks any activity-based candidate dated earlier than today, however
	// different its value - recomputeAndRecord used to record it anyway, appending a dead row
	// that could never actually become current (see summaryFor/ledgerFor: "current" is whichever
	// row has the latest effective_from) and would keep re-inserting itself, with a new id, every
	// time the ingest hook or a manual refresh re-evaluated the same activity. Seen for real: a
	// stale manual FTP entry (255W) outranked a genuine 225W ride-based candidate dated three
	// weeks earlier, and every re-trigger silently added another identical dead 225W row.
	@Test
	void refreshDoesNotRecordACandidateDatedBeforeTheCurrentEntry() {
		User athlete = newAthlete("refresh-dated-before-current@example.cc");
		thresholdHistoryService.recordManualValue(athlete, ThresholdField.FTP, 255, null);
		// 237W best-20min * 0.95 FTP_TEST_MULTIPLIER = 225.15, rounds to 225 - deliberately
		// different from the manual 255 so a naive value-only diff check would record it.
		newPowerActivity(athlete, Instant.now().minus(30, java.time.temporal.ChronoUnit.DAYS), 237, 1200);

		boolean changed = thresholdHistoryService.refreshField(athlete, ThresholdField.FTP);

		assertThat(changed).isFalse();
		List<ThresholdHistory> entries = thresholdHistoryRepository
				.findByAthleteIdAndFieldOrderByEffectiveFromDescIdDesc(athlete.getId(), ThresholdField.FTP);
		assertThat(entries).hasSize(1);
		assertThat(entries.get(0).getValueNumeric()).isEqualTo(255);
	}

	// Regression test for a real bug found live: deleteByAthleteIdAndField used to be a plain
	// Spring Data derived method, whose removal is only pending in the persistence context until
	// the next flush - but replayFullHistory's entityManager.clear() (called right after, inside
	// this same transaction) discards pending unflushed operations along with everything else, so
	// the old rows never actually got deleted and every rebuild appended a duplicate copy of the
	// whole ledger on top of the last one.
	@Test
	void rebuildingTwiceReplacesTheLedgerInsteadOfDuplicatingIt() {
		User athlete = newAthlete("rebuild-idempotent@example.cc");
		newPowerActivity(athlete, Instant.parse("2024-01-01T00:00:00Z"), 200, 1200);
		newPowerActivity(athlete, Instant.parse("2024-02-01T00:00:00Z"), 250, 1200);

		int firstCount = thresholdHistoryService.rebuildHistory(athlete, ThresholdField.FTP, (a, b) -> { });
		int secondCount = thresholdHistoryService.rebuildHistory(athlete, ThresholdField.FTP, (a, b) -> { });

		assertThat(firstCount).isEqualTo(2);
		assertThat(secondCount).isEqualTo(firstCount);
		assertThat(thresholdHistoryRepository
				.findByAthleteIdAndFieldOrderByEffectiveFromDescIdDesc(athlete.getId(), ThresholdField.FTP)).hasSize(2);
	}
}
