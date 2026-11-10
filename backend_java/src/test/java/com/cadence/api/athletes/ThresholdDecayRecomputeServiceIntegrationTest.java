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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Integration tests for the decay-enabled wiring (issue #485, slice 3) - ingest, manual edit,
 * refresh, and rebuild all going through ThresholdDecayRecomputeService.resimulateSuffix
 * instead of the cliff-based currentWindowValue/recomputeAndRecord/replayFullHistory path, for a
 * decay-enabled athlete. The pure simulate() mechanics themselves are already covered by
 * ThresholdDecayTest - this class is about the DB-backed plumbing around it. Mirrors the Python
 * backend's athletes/test_threshold_decay_recompute.py. */
class ThresholdDecayRecomputeServiceIntegrationTest extends IntegrationTest {

	@Autowired
	private ThresholdHistoryService thresholdHistoryService;
	@Autowired
	private ThresholdDecayRecomputeService decayRecomputeService;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private ActivityRepository activityRepository;
	@Autowired
	private RecordRepository recordRepository;
	@Autowired
	private ThresholdHistoryRepository thresholdHistoryRepository;

	private User newDecayAthlete(String email) {
		User user = new User();
		user.setEmail(email);
		user.setName("Decay Athlete");
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
	void recomputeForActivityCreatesAMeasuredAnchorForADecayEnabledAthlete() {
		// recomputeForActivity resimulates the whole suffix through the real today (design doc
		// 4.2) - an activity dated this far in the past (relative to windowDays=10) will have
		// long since decayed away from its own anchor value by the time the real clock catches
		// up, so this only checks the FIRST (measured, anchor) row, not the athlete's live value.
		User athlete = newDecayAthlete("decay-ingest@example.cc");
		Instant start = LocalDate.of(2026, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant();
		Activity activity = newPowerActivity(athlete, start, 260, 1200);

		thresholdHistoryService.recomputeForActivity(activity);

		List<ThresholdHistory> rows = thresholdHistoryRepository
				.findByAthleteIdAndFieldOrderByEffectiveFromDescIdDesc(athlete.getId(), ThresholdField.FTP);
		ThresholdHistory first = rows.get(rows.size() - 1);
		assertThat(first.getKind()).isEqualTo(ThresholdHistoryKind.MEASURED);
		assertThat(first.getValueNumeric()).isEqualTo(Math.round(0.95 * 260));
		assertThat(first.getEffectiveFrom()).isEqualTo(LocalDate.of(2026, 1, 1));
	}

	@Test
	void decayDisabledAthleteIsUnaffectedByTheNewWiring() {
		User athlete = new User();
		athlete.setEmail("decay-old-path@example.cc");
		athlete.setName("Old Path");
		athlete.setPassword("irrelevant-for-this-test");
		athlete = userRepository.save(athlete);
		assertThat(athlete.isThresholdDecayEnabled()).isFalse();
		Instant start = LocalDate.of(2026, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant();
		Activity activity = newPowerActivity(athlete, start, 260, 1200);

		thresholdHistoryService.recomputeForActivity(activity);

		ThresholdHistory entry = thresholdHistoryRepository
				.findFirstByAthleteIdAndFieldOrderByEffectiveFromDescIdDesc(athlete.getId(), ThresholdField.FTP).orElseThrow();
		assertThat(entry.getKind()).isEqualTo(ThresholdHistoryKind.MEASURED);
		assertThat(entry.getEffectiveFrom()).isEqualTo(entry.getCurrentFrom());
	}

	@Test
	void decayedRowsAccumulateOverAMultiDayResimulation() {
		User athlete = newDecayAthlete("decay-multiday@example.cc");
		LocalDate anchorDate = LocalDate.of(2026, 1, 1);
		Instant start = anchorDate.atStartOfDay(ZoneOffset.UTC).toInstant();
		Activity activity = newPowerActivity(athlete, start, 260, 1200);
		thresholdHistoryService.recomputeForActivity(activity);

		LocalDate expiry = anchorDate.plusDays(athlete.getThresholdWindowDays() + 1L);
		LocalDate end = expiry.plusDays(30);
		ThresholdDecayRecomputeService.ResimulateResult result =
				decayRecomputeService.resimulateSuffix(athlete, ThresholdField.FTP, expiry, end, null, null, true, false);

		assertThat(result.changed()).isTrue();
		List<ThresholdHistory> decayed = thresholdHistoryRepository
				.findByAthleteIdAndFieldOrderByEffectiveFromDescIdDesc(athlete.getId(), ThresholdField.FTP)
				.stream().filter(e -> e.getKind() == ThresholdHistoryKind.DECAYED).toList();
		assertThat(decayed).isNotEmpty();
		assertThat(decayed.get(decayed.size() - 1).getValueNumeric()).isLessThan(260);
	}

	@Test
	void refreshFieldAppendsOnlyAndEmitsADecayedRowOncePastExpiry() {
		// refreshField always resimulates through the real LocalDate.now() internally, with no
		// way to inject a fixed "today" - so this brings the ledger up to a known state as of
		// yesterday (relative to the real clock) via a directly-controlled resimulateSuffix call
		// first, then calls the real refreshField and confirms it only ever appends.
		User athlete = newDecayAthlete("decay-refresh@example.cc");
		LocalDate anchorDate = LocalDate.now().minusDays(athlete.getThresholdWindowDays() + 3L);
		Instant start = anchorDate.atStartOfDay(ZoneOffset.UTC).toInstant();
		newPowerActivity(athlete, start, 260, 1200);
		LocalDate yesterday = LocalDate.now().minusDays(1);
		decayRecomputeService.resimulateSuffix(athlete, ThresholdField.FTP, anchorDate, yesterday, null, null, true, false);
		Set<Long> idsBefore = new HashSet<>(thresholdHistoryRepository
				.findByAthleteIdAndFieldOrderByEffectiveFromDescIdDesc(athlete.getId(), ThresholdField.FTP)
				.stream().map(ThresholdHistory::getId).toList());
		assertThat(idsBefore.size()).isGreaterThan(1);

		boolean changed = thresholdHistoryService.refreshField(athlete, ThresholdField.FTP);

		assertThat(changed).isTrue();
		List<ThresholdHistory> rowsAfter =
				thresholdHistoryRepository.findByAthleteIdAndFieldOrderByEffectiveFromDescIdDesc(athlete.getId(), ThresholdField.FTP);
		List<ThresholdHistory> newRows = rowsAfter.stream().filter(e -> !idsBefore.contains(e.getId())).toList();
		assertThat(newRows).hasSize(1);
		assertThat(newRows.get(0).getCurrentFrom()).isEqualTo(LocalDate.now());
		assertThat(newRows.get(0).getKind()).isEqualTo(ThresholdHistoryKind.DECAYED);
		assertThat(rowsAfter.stream().filter(e -> idsBefore.contains(e.getId())).count()).isEqualTo(idsBefore.size());
	}

	@Test
	void manualEditCreatesItsOwnRowWithSetByViaResimulation() {
		User athlete = newDecayAthlete("decay-manual@example.cc");
		User coach = new User();
		coach.setEmail("decay-coach@example.cc");
		coach.setName("Coach");
		coach.setPassword("irrelevant-for-this-test");
		coach = userRepository.save(coach);

		ThresholdHistoryService.ManualEditResult result =
				thresholdHistoryService.recordManualValue(athlete, ThresholdField.FTP, 255, null, coach, null, true, false);

		assertThat(result.changed()).isTrue();
		assertThat(result.becameCurrent()).isTrue();
		assertThat(result.isCorrectionOnly()).isFalse();
		ThresholdHistory entry = thresholdHistoryRepository
				.findFirstByAthleteIdAndFieldOrderByEffectiveFromDescIdDesc(athlete.getId(), ThresholdField.FTP).orElseThrow();
		assertThat(entry.getKind()).isEqualTo(ThresholdHistoryKind.MANUAL);
		assertThat(entry.getValueNumeric()).isEqualTo(255);
		assertThat(entry.getSetBy().getId()).isEqualTo(coach.getId());
	}

	@Test
	void correctionOnlyEditStillUsesTheDirectWritePath() {
		// recordManualValue has no asOf override (unlike the Python backend's) - staleOnArrival
		// is always computed against the real LocalDate.now(), so laterDate must be "now" itself
		// for the first edit to actually become current (not stale on arrival); the backdated
		// one then becomes correction-only via hasLaterRow, not staleness.
		User athlete = newDecayAthlete("decay-correction@example.cc");
		LocalDate laterDate = LocalDate.now();
		thresholdHistoryService.recordManualValue(athlete, ThresholdField.FTP, 250, null, athlete, laterDate, true, false);

		ThresholdHistoryService.ManualEditResult backdated = thresholdHistoryService.recordManualValue(
				athlete, ThresholdField.FTP, 230, null, athlete, laterDate.minusDays(5), true, false);

		assertThat(backdated.changed()).isTrue();
		assertThat(backdated.isCorrectionOnly()).isTrue();
		assertThat(backdated.becameCurrent()).isFalse();
		ThresholdHistory current = thresholdHistoryRepository
				.findFirstByAthleteIdAndFieldAndCorrectionOnlyFalseOrderByEffectiveFromDescIdDesc(athlete.getId(), ThresholdField.FTP)
				.orElseThrow();
		assertThat(current.getValueNumeric()).isEqualTo(250);
	}

	@Test
	void rebuildKeepsManualRowsAndRegeneratesMeasuredAndDecayed() {
		User athlete = newDecayAthlete("decay-rebuild@example.cc");
		LocalDate manualDate = LocalDate.of(2026, 1, 1);
		// Lower than the activity's own implied value (round(0.95*260)=247) below, so the
		// activity legitimately beats it as an upward reset.
		thresholdHistoryService.recordManualValue(athlete, ThresholdField.FTP, 200, null, athlete, manualDate, true, false);
		Instant activityStart = LocalDate.of(2026, 1, 10).atStartOfDay(ZoneOffset.UTC).toInstant();
		Activity activity = newPowerActivity(athlete, activityStart, 260, 1200);
		thresholdHistoryService.recomputeForActivity(activity);
		ThresholdHistory strayDecayed = new ThresholdHistory();
		strayDecayed.setAthlete(athlete);
		strayDecayed.setField(ThresholdField.FTP);
		strayDecayed.setValueNumeric(240);
		strayDecayed.setEffectiveFrom(LocalDate.of(2026, 2, 1));
		strayDecayed.setCurrentFrom(LocalDate.of(2026, 2, 1));
		strayDecayed.setKind(ThresholdHistoryKind.DECAYED);
		Long strayDecayedId = thresholdHistoryRepository.save(strayDecayed).getId();

		thresholdHistoryService.rebuildHistory(athlete, ThresholdField.FTP, (current, total) -> { });

		List<ThresholdHistory> rows =
				thresholdHistoryRepository.findByAthleteIdAndFieldOrderByEffectiveFromDescIdDesc(athlete.getId(), ThresholdField.FTP);
		assertThat(rows).anyMatch(e -> e.getValueNumeric() != null && e.getValueNumeric() == 200);
		assertThat(rows).noneMatch(e -> e.getId().equals(strayDecayedId));
		assertThat(rows).anyMatch(e -> e.getKind() == ThresholdHistoryKind.MEASURED);
	}
}
