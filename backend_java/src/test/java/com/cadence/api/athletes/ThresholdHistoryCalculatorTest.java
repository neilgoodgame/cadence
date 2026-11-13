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

/** currentWindowValue/replayFullHistory - the pure rolling-window algorithm, tested
 * independently of any endpoint. Default thresholdWindowDays=112, thresholdSanityPct=30 unless a
 * test overrides them. */
class ThresholdHistoryCalculatorTest extends IntegrationTest {

	@Autowired
	private ThresholdHistoryCalculator calculator;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private ActivityRepository activityRepository;
	@Autowired
	private RecordRepository recordRepository;
	@Autowired
	private ThresholdHistoryRepository thresholdHistoryRepository;

	private User athlete;

	private User newAthlete(String email, Integer ftp) {
		User user = new User();
		user.setEmail(email);
		user.setName("Test Athlete");
		user.setPassword("irrelevant-for-this-test");
		user.setFtp(ftp);
		return userRepository.save(user);
	}

	private Activity newPowerActivity(User owner, Sport sport, Instant startDate, int power, int durationSeconds) {
		return newPowerActivity(owner, sport, startDate, (Integer) power, durationSeconds, null);
	}

	private Activity newPowerActivity(User owner, Sport sport, Instant startDate, Integer power, int durationSeconds,
			com.cadence.api.athletes.RunningPowerSource powerSource) {
		Activity activity = new Activity();
		activity.setAthlete(owner);
		activity.setSport(sport);
		activity.setName("Ride");
		activity.setStartDate(startDate);
		activity.setMovingTime(durationSeconds);
		activity.setPowerSource(powerSource);
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

	private Activity newPaceActivity(User owner, Instant startDate, int paceSecondsPerKm, int durationSeconds) {
		Activity activity = new Activity();
		activity.setAthlete(owner);
		activity.setSport(Sport.RUN);
		activity.setName("Run");
		activity.setStartDate(startDate);
		activity.setMovingTime(durationSeconds);
		activity = activityRepository.save(activity);
		for (int t = 0; t <= durationSeconds; t++) {
			Record record = new Record();
			record.setId(new RecordId(activity.getId(), startDate.plusSeconds(t)));
			record.setActivity(activity);
			record.setT(t);
			record.setDistanceKm((double) t / paceSecondsPerKm);
			recordRepository.save(record);
		}
		return activity;
	}

	private ThresholdHistory newEntry(
			User owner, ThresholdField field, Activity source, int valueNumeric, LocalDate effectiveFrom, ThresholdHistoryKind kind) {
		ThresholdHistory entry = new ThresholdHistory();
		entry.setAthlete(owner);
		entry.setField(field);
		entry.setValueNumeric(valueNumeric);
		entry.setSourceActivity(source);
		entry.setEffectiveFrom(effectiveFrom);
		entry.setCurrentFrom(effectiveFrom);
		entry.setKind(kind);
		return thresholdHistoryRepository.save(entry);
	}

	// --- currentWindowValue ---

	@Test
	void picksBestQualifyingActivityWithinWindow() {
		athlete = newAthlete("threshold-history-best@example.cc", 200);
		// Both within the default 30% sanity band around ftp=200 (140-260) - this test is about
		// picking the best *qualifying* candidate, not sanity filtering.
		newPowerActivity(athlete, Sport.BIKE, Instant.parse("2026-05-01T07:00:00Z"), 230, 1200);
		Activity strong = newPowerActivity(athlete, Sport.BIKE, Instant.parse("2026-05-15T07:00:00Z"), 250, 1200);

		ThresholdHistoryCalculator.Candidate candidate =
				calculator.currentWindowValue(athlete, ThresholdField.FTP, LocalDate.of(2026, 6, 1));

		assertThat(candidate).isNotNull();
		assertThat(candidate.activityId()).isEqualTo(strong.getId());
		assertThat(candidate.impliedValue()).isEqualTo(Math.round(0.95 * 250));
	}

	@Test
	void ignoresActivitiesOutsideWindow() {
		athlete = newAthlete("threshold-history-window@example.cc", 200);
		newPowerActivity(athlete, Sport.BIKE, Instant.parse("2025-01-01T07:00:00Z"), 400, 1200);
		Activity recent = newPowerActivity(athlete, Sport.BIKE, Instant.parse("2026-05-15T07:00:00Z"), 250, 1200);

		ThresholdHistoryCalculator.Candidate candidate =
				calculator.currentWindowValue(athlete, ThresholdField.FTP, LocalDate.of(2026, 6, 1));

		assertThat(candidate.activityId()).isEqualTo(recent.getId());
	}

	@Test
	void ignoresWrongSport() {
		athlete = newAthlete("threshold-history-sport@example.cc", 200);
		newPowerActivity(athlete, Sport.RUN, Instant.parse("2026-05-15T07:00:00Z"), 500, 1200); // not a bike ride

		ThresholdHistoryCalculator.Candidate candidate =
				calculator.currentWindowValue(athlete, ThresholdField.FTP, LocalDate.of(2026, 6, 1));

		assertThat(candidate).isNull();
	}

	@Test
	void excludesADuplicateMarkedActivity() {
		// Issue #498: a same-ride-twice duplicate shouldn't get a second shot at becoming a
		// threshold candidate the primary already represents - the stronger (250W) effort here
		// is marked as a duplicate, so the weaker (230W) one should win instead.
		athlete = newAthlete("threshold-history-dup@example.cc", 200);
		Activity primary = newPowerActivity(athlete, Sport.BIKE, Instant.parse("2026-05-01T07:00:00Z"), 230, 1200);
		Activity duplicate = newPowerActivity(athlete, Sport.BIKE, Instant.parse("2026-05-15T07:00:00Z"), 250, 1200);
		duplicate.setPrimaryActivity(primary);
		activityRepository.save(duplicate);

		ThresholdHistoryCalculator.Candidate candidate =
				calculator.currentWindowValue(athlete, ThresholdField.FTP, LocalDate.of(2026, 6, 1));

		assertThat(candidate.activityId()).isEqualTo(primary.getId());
		assertThat(candidate.impliedValue()).isEqualTo(Math.round(0.95 * 230));
	}

	@Test
	void shortActivityDoesNotQualify() {
		athlete = newAthlete("threshold-history-short@example.cc", 200);
		// 10 minutes - shorter than the 20-minute FTP test window.
		newPowerActivity(athlete, Sport.BIKE, Instant.parse("2026-05-15T07:00:00Z"), 400, 600);

		ThresholdHistoryCalculator.Candidate candidate =
				calculator.currentWindowValue(athlete, ThresholdField.FTP, LocalDate.of(2026, 6, 1));

		assertThat(candidate).isNull();
	}

	@Test
	void excludesOutlierViaSanityCheck() {
		athlete = newAthlete("threshold-history-outlier@example.cc", 200);
		// Implies ~401 (100%+ higher than 200, well past the default 30% sanity band) - treated
		// as implausible (e.g. corrupt power data).
		newPowerActivity(athlete, Sport.BIKE, Instant.parse("2026-05-15T07:00:00Z"), 422, 1200);

		ThresholdHistoryCalculator.Candidate candidate =
				calculator.currentWindowValue(athlete, ThresholdField.FTP, LocalDate.of(2026, 6, 1));

		assertThat(candidate).isNull();
	}

	@Test
	void firstEverValueHasNoSanityCheck() {
		athlete = newAthlete("threshold-history-fresh@example.cc", null);
		newPowerActivity(athlete, Sport.BIKE, Instant.parse("2026-05-15T07:00:00Z"), 500, 1200);

		ThresholdHistoryCalculator.Candidate candidate =
				calculator.currentWindowValue(athlete, ThresholdField.FTP, LocalDate.of(2026, 6, 1));

		assertThat(candidate).isNotNull(); // no reference yet - nothing to sanity-check against
		assertThat(candidate.impliedValue()).isEqualTo(Math.round(0.95 * 500));
	}

	@Test
	void sixtyMinDirectUsesRawSixtyMinutePowerNotTheTwentyMinuteMultiplier() {
		athlete = newAthlete("threshold-history-sixty-min@example.cc", 200);
		athlete.setFtpCalculationMethod(FtpCalculationMethod.SIXTY_MIN_DIRECT);
		athlete = userRepository.save(athlete);
		newPowerActivity(athlete, Sport.BIKE, Instant.parse("2026-05-15T07:00:00Z"), 240, 3600);

		ThresholdHistoryCalculator.Candidate candidate =
				calculator.currentWindowValue(athlete, ThresholdField.FTP, LocalDate.of(2026, 6, 1));

		// The raw 60-min average (240), not 0.95 * 240 (228) - the TWENTY_MIN_TEST default's math.
		assertThat(candidate).isNotNull();
		assertThat(candidate.impliedValue()).isEqualTo(240);
	}

	@Test
	void sixtyMinDirectDoesNotQualifyFromA20MinuteEffort() {
		athlete = newAthlete("threshold-history-sixty-min-short@example.cc", 200);
		athlete.setFtpCalculationMethod(FtpCalculationMethod.SIXTY_MIN_DIRECT);
		athlete = userRepository.save(athlete);
		// Long enough for TWENTY_MIN_TEST, too short for a real 60-minute window.
		newPowerActivity(athlete, Sport.BIKE, Instant.parse("2026-05-15T07:00:00Z"), 240, 1200);

		ThresholdHistoryCalculator.Candidate candidate =
				calculator.currentWindowValue(athlete, ThresholdField.FTP, LocalDate.of(2026, 6, 1));

		assertThat(candidate).isNull();
	}

	@Test
	void paceLowerIsBetter() {
		athlete = newAthlete("threshold-history-pace@example.cc", null);
		athlete.setThresholdPace("5:00");
		athlete = userRepository.save(athlete);
		newPaceActivity(athlete, Instant.parse("2026-05-01T07:00:00Z"), 280, 3600); // 4:40/km
		Activity faster = newPaceActivity(athlete, Instant.parse("2026-05-15T07:00:00Z"), 270, 3600); // 4:30/km

		ThresholdHistoryCalculator.Candidate candidate =
				calculator.currentWindowValue(athlete, ThresholdField.THRESHOLD_PACE, LocalDate.of(2026, 6, 1));

		assertThat(candidate.activityId()).isEqualTo(faster.getId());
		assertThat(candidate.impliedValue()).isCloseTo(270.0, org.assertj.core.data.Offset.offset(1.0));
	}

	@Test
	void criticalRunPowerIgnoresAMatchingActivityWithNoRealPowerDataAtAll() {
		// A real edge case, not hypothetical: under "native" preference, a Stryd-only file (no
		// native power meter at all) resolves to a powerSource that trivially *matches* the
		// preference (both NATIVE, since that's what governed the resolution at ingest), but
		// every sample is genuinely null - bestAverage treats a missing sample as 0W, not "no
		// data," so this must be caught before it reaches that, or a Stryd-only activity
		// ingested under "native" preference would imply an athlete has a 0W critical running
		// power instead of no candidate at all.
		athlete = newAthlete("threshold-history-no-real-power@example.cc", null);
		athlete.setRunningPowerSource(com.cadence.api.athletes.RunningPowerSource.NATIVE);
		athlete = userRepository.save(athlete);
		newPowerActivity(athlete, Sport.RUN, Instant.parse("2026-02-01T07:00:00Z"), (Integer) null, 3600,
				com.cadence.api.athletes.RunningPowerSource.NATIVE);

		ThresholdHistoryCalculator.Candidate candidate =
				calculator.currentWindowValue(athlete, ThresholdField.CRITICAL_RUN_POWER, LocalDate.of(2026, 2, 15));

		assertThat(candidate).isNull();
	}

	@Test
	void ftpExcludesAnActivityWhoseBikePowerSourceDisagreesWithPreference() {
		athlete = newAthlete("threshold-history-bike-power-mismatch@example.cc", 200);
		athlete.setBikePowerSource(com.cadence.api.athletes.BikePowerSource.PEDALS);
		athlete = userRepository.save(athlete);
		Activity activity = newPowerActivity(athlete, Sport.BIKE, Instant.parse("2026-05-15T07:00:00Z"), 240, 1200);
		activity.setBikePowerSource(com.cadence.api.athletes.BikePowerSource.TRAINER);
		activityRepository.save(activity);

		ThresholdHistoryCalculator.Candidate candidate =
				calculator.currentWindowValue(athlete, ThresholdField.FTP, LocalDate.of(2026, 6, 1));

		assertThat(candidate).isNull();
	}

	@Test
	void ftpIncludesAnActivityWhoseBikePowerSourceMatchesPreference() {
		athlete = newAthlete("threshold-history-bike-power-match@example.cc", 200);
		athlete.setBikePowerSource(com.cadence.api.athletes.BikePowerSource.PEDALS);
		athlete = userRepository.save(athlete);
		Activity activity = newPowerActivity(athlete, Sport.BIKE, Instant.parse("2026-05-15T07:00:00Z"), 240, 1200);
		activity.setBikePowerSource(com.cadence.api.athletes.BikePowerSource.PEDALS);
		activityRepository.save(activity);

		ThresholdHistoryCalculator.Candidate candidate =
				calculator.currentWindowValue(athlete, ThresholdField.FTP, LocalDate.of(2026, 6, 1));

		assertThat(candidate).isNotNull();
		assertThat(candidate.impliedValue()).isEqualTo(Math.round(0.95 * 240));
	}

	@Test
	void ftpIncludesAnUnattachedActivityRegardlessOfPreference() {
		// The common case: bikePowerSource is only ever set by an attach/swap (issue #503 phase
		// 1) - an activity that's never had a second source attached has a null bikePowerSource,
		// which must always match, or every FTP candidate would vanish the moment an athlete sets
		// a preference without ever actually attaching anything.
		athlete = newAthlete("threshold-history-bike-power-unattached@example.cc", 200);
		athlete.setBikePowerSource(com.cadence.api.athletes.BikePowerSource.PEDALS);
		athlete = userRepository.save(athlete);
		newPowerActivity(athlete, Sport.BIKE, Instant.parse("2026-05-15T07:00:00Z"), 240, 1200);

		ThresholdHistoryCalculator.Candidate candidate =
				calculator.currentWindowValue(athlete, ThresholdField.FTP, LocalDate.of(2026, 6, 1));

		assertThat(candidate).isNotNull();
	}

	// --- currentWindowValue: manual anchor (issue #484) ---

	@Test
	void manualValueIsNotReplacedByALaterLowerActivity() {
		athlete = newAthlete("threshold-history-manual-not-replaced@example.cc", 255);
		newEntry(athlete, ThresholdField.FTP, null, 255, LocalDate.of(2026, 1, 1), ThresholdHistoryKind.MANUAL);
		// Implies ~220W - within the 30% sanity band around 255, but lower than it.
		newPowerActivity(athlete, Sport.BIKE, Instant.parse("2026-02-01T07:00:00Z"), 232, 1200);

		ThresholdHistoryCalculator.Candidate candidate =
				calculator.currentWindowValue(athlete, ThresholdField.FTP, LocalDate.of(2026, 3, 1));

		assertThat(candidate).isNotNull();
		assertThat(candidate.activityId()).isNull();
		assertThat(candidate.impliedValue()).isEqualTo(255);
	}

	@Test
	void manualValueIsReplacedByALaterBetterActivity() {
		athlete = newAthlete("threshold-history-manual-replaced@example.cc", 255);
		newEntry(athlete, ThresholdField.FTP, null, 255, LocalDate.of(2026, 1, 1), ThresholdHistoryKind.MANUAL);
		Activity better = newPowerActivity(athlete, Sport.BIKE, Instant.parse("2026-02-01T07:00:00Z"), 300, 1200);

		ThresholdHistoryCalculator.Candidate candidate =
				calculator.currentWindowValue(athlete, ThresholdField.FTP, LocalDate.of(2026, 3, 1));

		assertThat(candidate.activityId()).isEqualTo(better.getId());
		assertThat(candidate.impliedValue()).isEqualTo(Math.round(0.95 * 300));
	}

	@Test
	void manualValueAgesOutOfWindowLikeAnyOtherEntry() {
		athlete = newAthlete("threshold-history-manual-ages-out@example.cc", 255);
		athlete.setThresholdWindowDays(30);
		athlete = userRepository.save(athlete);
		newEntry(athlete, ThresholdField.FTP, null, 255, LocalDate.of(2026, 1, 1), ThresholdHistoryKind.MANUAL);
		Activity lower = newPowerActivity(athlete, Sport.BIKE, Instant.parse("2026-02-15T07:00:00Z"), 232, 1200);

		// More than 30 days after the manual entry - it's left the window, same as a measured one.
		ThresholdHistoryCalculator.Candidate candidate =
				calculator.currentWindowValue(athlete, ThresholdField.FTP, LocalDate.of(2026, 3, 1));

		assertThat(candidate.activityId()).isEqualTo(lower.getId());
	}

	@Test
	void aMeasuredAnchorIsUnaffectedByTheManualAnchorFix() {
		// The anchor's own source activity is already in the activities scan and wins there on
		// its own - the manual-anchor candidate must not double-count or otherwise interfere.
		athlete = newAthlete("threshold-history-measured-anchor@example.cc", 200);
		Activity strong = newPowerActivity(athlete, Sport.BIKE, Instant.parse("2026-01-01T07:00:00Z"), 270, 1200);
		newEntry(athlete, ThresholdField.FTP, strong, (int) Math.round(0.95 * 270), LocalDate.of(2026, 1, 1),
				ThresholdHistoryKind.MEASURED);
		newPowerActivity(athlete, Sport.BIKE, Instant.parse("2026-02-01T07:00:00Z"), 232, 1200);

		ThresholdHistoryCalculator.Candidate candidate =
				calculator.currentWindowValue(athlete, ThresholdField.FTP, LocalDate.of(2026, 3, 1));

		assertThat(candidate.activityId()).isEqualTo(strong.getId());
	}

	// --- replayFullHistory ---

	@Test
	void replayBuildsLedgerOfChangesOverTime() {
		athlete = newAthlete("threshold-history-replay@example.cc", 200);
		Activity first = newPowerActivity(athlete, Sport.BIKE, Instant.parse("2026-01-01T07:00:00Z"), 210, 1200);
		Activity second = newPowerActivity(athlete, Sport.BIKE, Instant.parse("2026-02-01T07:00:00Z"), 230, 1200);

		List<ThresholdHistoryCalculator.ThresholdHistoryEntry> entries =
				calculator.replayFullHistory(athlete, ThresholdField.FTP);

		assertThat(entries).hasSize(2);
		assertThat(entries.get(0).activityId()).isEqualTo(first.getId());
		assertThat(entries.get(0).value()).isEqualTo(Math.round(0.95 * 210));
		assertThat(entries.get(1).activityId()).isEqualTo(second.getId());
		assertThat(entries.get(1).value()).isEqualTo(Math.round(0.95 * 230));
	}

	@Test
	void candidateStatusesExcludesADuplicateMarkedActivity() {
		// Issue #498: a same-ride-twice duplicate shouldn't appear at all - not even as a
		// classified (QUALIFIED/REJECTED_SANITY) row, since it's not a real second effort.
		athlete = newAthlete("threshold-history-statuses-dup@example.cc", 200);
		Activity primary = newPowerActivity(athlete, Sport.BIKE, Instant.parse("2026-01-01T07:00:00Z"), 230, 1200);
		Activity duplicate = newPowerActivity(athlete, Sport.BIKE, Instant.parse("2026-01-01T07:00:00Z"), 230, 1200);
		duplicate.setPrimaryActivity(primary);
		activityRepository.save(duplicate);

		List<ThresholdHistoryCalculator.CandidateStatusEntry> statuses =
				calculator.candidateStatuses(athlete, ThresholdField.FTP, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));

		assertThat(statuses).extracting(ThresholdHistoryCalculator.CandidateStatusEntry::activityId)
				.containsExactly(primary.getId());
	}

	@Test
	void replayExcludesADuplicateMarkedActivity() {
		// Issue #498: the stronger (260W) effort is marked as a duplicate of the weaker (220W)
		// one - the replay must never let it contribute a ledger entry at all.
		athlete = newAthlete("threshold-history-replay-dup@example.cc", 200);
		Activity primary = newPowerActivity(athlete, Sport.BIKE, Instant.parse("2026-01-01T07:00:00Z"), 220, 1200);
		Activity duplicate = newPowerActivity(athlete, Sport.BIKE, Instant.parse("2026-01-01T07:00:00Z"), 260, 1200);
		duplicate.setPrimaryActivity(primary);
		activityRepository.save(duplicate);

		List<ThresholdHistoryCalculator.ThresholdHistoryEntry> entries =
				calculator.replayFullHistory(athlete, ThresholdField.FTP);

		assertThat(entries).extracting(ThresholdHistoryCalculator.ThresholdHistoryEntry::activityId)
				.containsExactly(primary.getId());
		assertThat(entries.get(0).value()).isEqualTo(Math.round(0.95 * 220));
	}

	@Test
	void replayDropsValueWhenSourceAgesOut() {
		athlete = newAthlete("threshold-history-ages-out@example.cc", 200);
		Activity strong = newPowerActivity(athlete, Sport.BIKE, Instant.parse("2026-01-01T07:00:00Z"), 250, 1200);
		// >112 days later - the earlier ride has aged out of the window by the time this weaker
		// (but still plausible) one is processed, so it becomes the new, lower current value.
		Activity weak = newPowerActivity(athlete, Sport.BIKE, Instant.parse("2026-08-01T07:00:00Z"), 220, 1200);

		List<ThresholdHistoryCalculator.ThresholdHistoryEntry> entries =
				calculator.replayFullHistory(athlete, ThresholdField.FTP);

		assertThat(entries).hasSize(2);
		assertThat(entries.get(0).activityId()).isEqualTo(strong.getId());
		assertThat(entries.get(1).activityId()).isEqualTo(weak.getId());
		assertThat(entries.get(1).value()).isLessThan(entries.get(0).value());
		// weak wins on its own processing pass here - currentFrom and effectiveFrom coincide.
		assertThat(entries.get(1).currentFrom()).isEqualTo(entries.get(1).effectiveFrom());
	}

	@Test
	void replayCurrentFromLagsEffectiveFromWhenAWorseRowOnlyWinsLater() {
		// The exact real-world scenario this field exists for: a worse-but-still-qualifying ride
		// sits dormant in the window behind a better one, then only becomes current once a THIRD,
		// unrelated, later activity's own processing pass notices the better one has aged out -
		// currentFrom must be stamped with that third activity's date, not the winner's own.
		athlete = newAthlete("threshold-history-cascading-expiry@example.cc", 200);
		Activity better = newPowerActivity(athlete, Sport.BIKE, Instant.parse("2026-01-01T07:00:00Z"), 250, 1200);
		Activity worse = newPowerActivity(athlete, Sport.BIKE, Instant.parse("2026-01-09T07:00:00Z"), 220, 1200);
		// >112 days after `better` (2026-01-01), but still within 112 days of `worse`
		// (2026-01-09) - and too short (600s < the 1200s FTP test window) to contribute its own
		// candidacy, so it's purely the trigger, not itself a candidate.
		Activity trigger = newPowerActivity(athlete, Sport.BIKE, Instant.parse("2026-04-25T07:00:00Z"), 999, 600);

		List<ThresholdHistoryCalculator.ThresholdHistoryEntry> entries =
				calculator.replayFullHistory(athlete, ThresholdField.FTP);

		assertThat(entries).extracting(ThresholdHistoryCalculator.ThresholdHistoryEntry::activityId)
				.containsExactly(better.getId(), worse.getId());
		assertThat(entries.get(1).effectiveFrom()).isEqualTo(worse.getStartDate().atZone(java.time.ZoneOffset.UTC).toLocalDate());
		assertThat(entries.get(1).currentFrom()).isEqualTo(trigger.getStartDate().atZone(java.time.ZoneOffset.UTC).toLocalDate());
		assertThat(entries.get(1).effectiveFrom()).isNotEqualTo(entries.get(1).currentFrom());
	}

	@Test
	void replayExcludesOutlierFromLedger() {
		athlete = newAthlete("threshold-history-replay-outlier@example.cc", 200);
		newPowerActivity(athlete, Sport.BIKE, Instant.parse("2026-01-01T07:00:00Z"), 210, 1200);
		newPowerActivity(athlete, Sport.BIKE, Instant.parse("2026-01-15T07:00:00Z"), 500, 1200); // implausible spike

		List<ThresholdHistoryCalculator.ThresholdHistoryEntry> entries =
				calculator.replayFullHistory(athlete, ThresholdField.FTP);

		assertThat(entries).hasSize(1); // the spike never enters the ledger
	}

	@Test
	void replayGradualImprovementNotBlockedByCumulativeSanityCheck() {
		// Each step is a plausible jump from the *previous* step, but the total change across
		// all steps (200 -> 285, +42.5%) would fail a naive "vs. the original value" sanity check
		// - checking each candidate against the running reference (not the very first value)
		// must not block genuine, gradual improvement like this.
		athlete = newAthlete("threshold-history-gradual@example.cc", 200);
		Instant startDate = Instant.parse("2026-01-01T07:00:00Z");
		int[] powers = {210, 240, 270, 300};
		for (int i = 0; i < powers.length; i++) {
			newPowerActivity(athlete, Sport.BIKE, startDate.plusSeconds(30L * i * 86400), powers[i], 1200);
		}

		List<ThresholdHistoryCalculator.ThresholdHistoryEntry> entries =
				calculator.replayFullHistory(athlete, ThresholdField.FTP);

		assertThat(entries).hasSize(powers.length);
		assertThat(entries.get(entries.size() - 1).value()).isEqualTo(Math.round(0.95 * powers[powers.length - 1]));
	}

	// --- replayFrom (issue #545) ---

	private ThresholdHistory persistRow(User owner, ThresholdField field, Activity source, int power,
			LocalDate effectiveFrom, LocalDate currentFrom) {
		ThresholdHistory entry = new ThresholdHistory();
		entry.setAthlete(owner);
		entry.setField(field);
		entry.setValueNumeric((int) Math.round(0.95 * power)); // same implied-FTP math as the replay
		entry.setSourceActivity(source);
		entry.setEffectiveFrom(effectiveFrom);
		entry.setCurrentFrom(currentFrom);
		entry.setKind(ThresholdHistoryKind.MEASURED);
		return thresholdHistoryRepository.save(entry);
	}

	private static List<ThresholdHistoryCalculator.ThresholdHistoryEntry> tailFrom(
			List<ThresholdHistoryCalculator.ThresholdHistoryEntry> entries, LocalDate fromDate) {
		return entries.stream().filter(e -> !e.currentFrom().isBefore(fromDate)).toList();
	}

	@Test
	void replayFromChecksPreScanCandidatesAgainstTheSeededReferenceNotJustTheAnchor() {
		// The seed-boundary case an anchor-only seed gets wrong: Y (300W) is the true moving
		// reference when C (245W) arrives, but Y is older than fromDate - windowDays and so
		// invisible to a seed that only knows the latest row (A, 280W). Against A's value C
		// would pass the band (12.5%); against Y's it fails (18.3%) - and a full replay rejects
		// it, so replayFrom must too (otherwise C later emits a ledger row the full replay
		// never writes).
		athlete = newAthlete("threshold-history-from-seedref@example.cc", 200);
		athlete.setThresholdWindowDays(10);
		athlete.setThresholdSanityPct(15);
		Activity y = newPowerActivity(athlete, Sport.BIKE, Instant.parse("2026-01-17T07:00:00Z"), 300, 1200);
		Activity a = newPowerActivity(athlete, Sport.BIKE, Instant.parse("2026-01-23T07:00:00Z"), 280, 1200);
		newPowerActivity(athlete, Sport.BIKE, Instant.parse("2026-01-26T07:00:00Z"), 245, 1200); // C - borderline
		newPowerActivity(athlete, Sport.BIKE, Instant.parse("2026-01-28T07:00:00Z"), 999, 600); // too short
		newPowerActivity(athlete, Sport.BIKE, Instant.parse("2026-02-03T07:00:00Z"), 999, 600); // too short

		LocalDate fromDate = LocalDate.of(2026, 1, 30);
		List<ThresholdHistoryCalculator.ThresholdHistoryEntry> full = calculator.replayFullHistory(athlete, ThresholdField.FTP);
		assertThat(full).hasSize(2); // Y on its own date, A when Y ages out (Jan 28)
		assertThat(full.get(1).currentFrom()).isEqualTo(LocalDate.of(2026, 1, 28));

		// The pre-change ledger state: both rows exist with currentFrom before fromDate.
		persistRow(athlete, ThresholdField.FTP, y, 300, LocalDate.of(2026, 1, 17), LocalDate.of(2026, 1, 17));
		persistRow(athlete, ThresholdField.FTP, a, 280, LocalDate.of(2026, 1, 23), LocalDate.of(2026, 1, 28));

		List<ThresholdHistoryCalculator.ThresholdHistoryEntry> from =
				calculator.replayFrom(athlete, ThresholdField.FTP, fromDate);

		assertThat(from).isEqualTo(tailFrom(full, fromDate)); // empty - C was rejected in both
	}

	@Test
	void replayFromEmitsACascadingExpiryRowAcrossTheBoundary() {
		// B (240W) never became current before fromDate, so it has no ledger row of its own -
		// a seed built from rows alone can't know it exists. It must still be re-derived by the
		// scan and emit its row (effectiveFrom before fromDate, currentFrom after) when A ages
		// out during the tail.
		athlete = newAthlete("threshold-history-from-cascade@example.cc", 200);
		athlete.setThresholdWindowDays(10);
		Activity a = newPowerActivity(athlete, Sport.BIKE, Instant.parse("2026-01-01T07:00:00Z"), 250, 1200);
		newPowerActivity(athlete, Sport.BIKE, Instant.parse("2026-01-03T07:00:00Z"), 240, 1200); // B - shadowed
		// Jan 12, not Jan 13: with a 10-day window B (Jan 3) ages out ON Jan 13 (the cutoff is
		// strict), so the trigger must land one day earlier for B to still be in the window.
		newPowerActivity(athlete, Sport.BIKE, Instant.parse("2026-01-12T07:00:00Z"), 999, 600); // too-short trigger

		LocalDate fromDate = LocalDate.of(2026, 1, 5);
		List<ThresholdHistoryCalculator.ThresholdHistoryEntry> full = calculator.replayFullHistory(athlete, ThresholdField.FTP);
		assertThat(full).hasSize(2);
		assertThat(full.get(1).effectiveFrom()).isEqualTo(LocalDate.of(2026, 1, 3));
		assertThat(full.get(1).currentFrom()).isEqualTo(LocalDate.of(2026, 1, 12));

		// The pre-change ledger state: only A's row exists.
		persistRow(athlete, ThresholdField.FTP, a, 250, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 1));

		List<ThresholdHistoryCalculator.ThresholdHistoryEntry> from =
				calculator.replayFrom(athlete, ThresholdField.FTP, fromDate);

		assertThat(from).isEqualTo(tailFrom(full, fromDate)); // exactly B's cross-boundary row
		assertThat(from).hasSize(1);
	}
}
