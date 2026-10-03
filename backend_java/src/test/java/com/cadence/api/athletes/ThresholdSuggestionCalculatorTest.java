package com.cadence.api.athletes;

import static org.assertj.core.api.Assertions.assertThat;

import com.cadence.api.activities.Activity;
import com.cadence.api.activities.ActivityRepository;
import com.cadence.api.activities.Record;
import com.cadence.api.activities.RecordId;
import com.cadence.api.activities.RecordRepository;
import com.cadence.api.athletes.ThresholdHistoryCalculator.Candidate;
import com.cadence.api.athletes.ThresholdHistoryCalculator.RejectedCandidate;
import com.cadence.api.athletes.ThresholdHistoryCalculator.UpcomingDropResult;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.races.Race;
import com.cadence.api.races.RaceRepository;
import com.cadence.api.support.IntegrationTest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** rejectedCandidates/upcomingDrop/warningLeadDays (ThresholdHistoryCalculator) - the Threshold
 * suggestions feature's pure detection methods, tested independently of the API/caching layer
 * (see ThresholdSuggestionServiceTest for that). Default thresholdWindowDays=112,
 * thresholdSanityPct=30, thresholdWarningDays=21 unless a test overrides them. */
class ThresholdSuggestionCalculatorTest extends IntegrationTest {

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
	@Autowired
	private AcceptedThresholdCandidateRepository acceptedRepository;
	@Autowired
	private RaceRepository raceRepository;

	private User newAthlete(String email, Integer ftp) {
		User user = new User();
		user.setEmail(email);
		user.setName("Test Athlete");
		user.setPassword("irrelevant-for-this-test");
		user.setFtp(ftp);
		return userRepository.save(user);
	}

	private Activity newPowerActivity(User owner, Sport sport, Instant startDate, int power, int durationSeconds) {
		Activity activity = new Activity();
		activity.setAthlete(owner);
		activity.setSport(sport);
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

	private ThresholdHistory newLedgerEntry(User athlete, ThresholdField field, Integer valueNumeric, String valuePace,
			LocalDate effectiveFrom) {
		ThresholdHistory entry = new ThresholdHistory();
		entry.setAthlete(athlete);
		entry.setField(field);
		if (valueNumeric != null) {
			entry.setValueNumeric(valueNumeric);
		}
		if (valuePace != null) {
			entry.setValuePace(valuePace);
		}
		entry.setEffectiveFrom(effectiveFrom);
		entry.setCurrentFrom(effectiveFrom);
		return thresholdHistoryRepository.save(entry);
	}

	// --- rejectedCandidates ---

	@Test
	void rejectedCandidatesOnlyUpwardOutliers() {
		// ftp=200, sanity 30% -> band is 140-260. A 20-min power of 500W implies ~475 (way above
		// band, an improvement) - rejected and surfaced. A 20-min power of 50W implies ~47 (way
		// below band, a corrupt-low reading, not "maybe you're fitter now") - rejected but NOT
		// surfaced as a suggestion.
		User athlete = newAthlete("threshold-sug-upward@example.cc", 200);
		Activity high = newPowerActivity(athlete, Sport.BIKE, Instant.parse("2026-05-01T07:00:00Z"), 500, 1200);
		newPowerActivity(athlete, Sport.BIKE, Instant.parse("2026-05-15T07:00:00Z"), 50, 1200);

		List<RejectedCandidate> candidates = calculator.rejectedCandidates(athlete, ThresholdField.FTP, LocalDate.of(2026, 6, 1));

		assertThat(candidates).extracting(RejectedCandidate::activityId).containsExactly(high.getId());
	}

	@Test
	void rejectedCandidatesBestFirst() {
		User athlete = newAthlete("threshold-sug-best-first@example.cc", 200);
		Activity weaker = newPowerActivity(athlete, Sport.BIKE, Instant.parse("2026-05-01T07:00:00Z"), 500, 1200);
		Activity stronger = newPowerActivity(athlete, Sport.BIKE, Instant.parse("2026-05-15T07:00:00Z"), 550, 1200);

		List<RejectedCandidate> candidates = calculator.rejectedCandidates(athlete, ThresholdField.FTP, LocalDate.of(2026, 6, 1));

		assertThat(candidates).extracting(RejectedCandidate::activityId).containsExactly(stronger.getId(), weaker.getId());
	}

	@Test
	void rejectedCandidatesExcludesAnAcceptedOne() {
		User athlete = newAthlete("threshold-sug-accepted-excluded@example.cc", 200);
		Activity activity = newPowerActivity(athlete, Sport.BIKE, Instant.parse("2026-05-01T07:00:00Z"), 500, 1200);
		AcceptedThresholdCandidate accepted = new AcceptedThresholdCandidate();
		accepted.setAthlete(athlete);
		accepted.setField(ThresholdField.FTP);
		accepted.setActivity(activity);
		acceptedRepository.save(accepted);

		List<RejectedCandidate> candidates = calculator.rejectedCandidates(athlete, ThresholdField.FTP, LocalDate.of(2026, 6, 1));

		assertThat(candidates).isEmpty();
	}

	@Test
	void acceptedCandidateSurvivesReplayFullHistory() {
		User athlete = newAthlete("threshold-sug-accepted-survives@example.cc", 200);
		Activity activity = newPowerActivity(athlete, Sport.BIKE, Instant.parse("2026-05-01T07:00:00Z"), 500, 1200);
		AcceptedThresholdCandidate accepted = new AcceptedThresholdCandidate();
		accepted.setAthlete(athlete);
		accepted.setField(ThresholdField.FTP);
		accepted.setActivity(activity);
		acceptedRepository.save(accepted);

		List<ThresholdHistoryCalculator.ThresholdHistoryEntry> entries = calculator.replayFullHistory(athlete, ThresholdField.FTP);

		assertThat(entries).hasSize(1);
		assertThat(entries.get(0).activityId()).isEqualTo(activity.getId());
		assertThat(entries.get(0).value()).isEqualTo(Math.round(0.95 * 500));
	}

	@Test
	void rejectedCandidatesPaceLowerIsBetter() {
		User athlete = newAthlete("threshold-sug-pace-direction@example.cc", null);
		athlete.setThresholdPace("5:00");
		athlete = userRepository.save(athlete);
		// 30% sanity band around 300s/km is ~210-390s/km. 150s/km (2:30/km) is a huge PR -
		// rejected but an upward (faster) outlier, so it's surfaced. 500s/km (8:20/km) is
		// corrupt-slow - rejected, and correctly NOT surfaced.
		Activity faster = newPaceActivity(athlete, Instant.parse("2026-05-01T07:00:00Z"), 150, 3600);
		newPaceActivity(athlete, Instant.parse("2026-05-15T07:00:00Z"), 500, 3600);

		List<RejectedCandidate> candidates =
				calculator.rejectedCandidates(athlete, ThresholdField.THRESHOLD_PACE, LocalDate.of(2026, 6, 1));

		assertThat(candidates).extracting(RejectedCandidate::activityId).containsExactly(faster.getId());
	}

	// --- upcomingDrop ---

	@Test
	void upcomingDropShownExactlyAtTheLeadBoundary() {
		User athlete = newAthlete("threshold-sug-lead-boundary@example.cc", 250);
		LocalDate today = LocalDate.of(2026, 6, 1);
		int lead = ThresholdHistoryCalculator.warningLeadDays(athlete); // 21, default window=112 >= 84
		LocalDate effectiveFrom = today.minusDays(athlete.getThresholdWindowDays() + 1L - lead);
		newLedgerEntry(athlete, ThresholdField.FTP, 250, null, effectiveFrom);

		UpcomingDropResult result = calculator.upcomingDrop(athlete, ThresholdField.FTP, today);

		assertThat(result).isNotNull();
		assertThat(((UpcomingDropResult.UpcomingDrop) result).daysLeft()).isEqualTo(lead);
	}

	@Test
	void upcomingDropHiddenOneDayPastTheLeadBoundary() {
		User athlete = newAthlete("threshold-sug-past-boundary@example.cc", 250);
		LocalDate today = LocalDate.of(2026, 6, 1);
		int lead = ThresholdHistoryCalculator.warningLeadDays(athlete);
		LocalDate effectiveFrom = today.minusDays(athlete.getThresholdWindowDays() + 1L - (lead + 1));
		newLedgerEntry(athlete, ThresholdField.FTP, 250, null, effectiveFrom);

		UpcomingDropResult result = calculator.upcomingDrop(athlete, ThresholdField.FTP, today);

		assertThat(result).isNull();
	}

	@Test
	void warningLeadDaysScalesDownForAShortWindow() {
		User athlete = newAthlete("threshold-sug-short-window@example.cc", 200);
		athlete.setThresholdWindowDays(56); // < 84
		athlete.setThresholdWarningDays(21);
		athlete = userRepository.save(athlete);

		assertThat(ThresholdHistoryCalculator.warningLeadDays(athlete)).isEqualTo(14); // 56 / 4, not the raw 21 setting
	}

	@Test
	void upcomingDropOffSettingSuppressesEverything() {
		User athlete = newAthlete("threshold-sug-off@example.cc", 250);
		athlete.setThresholdWarningDays(0);
		athlete = userRepository.save(athlete);
		LocalDate today = LocalDate.of(2026, 6, 1);
		newLedgerEntry(athlete, ThresholdField.FTP, 250, null, today.minusDays(1));

		assertThat(calculator.upcomingDrop(athlete, ThresholdField.FTP, today)).isNull();
	}

	@Test
	void upcomingDropSuppressedForASmallDrop() {
		User athlete = newAthlete("threshold-sug-small-drop@example.cc", 250);
		LocalDate today = LocalDate.of(2026, 6, 1);
		int lead = ThresholdHistoryCalculator.warningLeadDays(athlete);
		LocalDate effectiveFrom = today.minusDays(athlete.getThresholdWindowDays() + 1L - lead);
		newLedgerEntry(athlete, ThresholdField.FTP, 250, null, effectiveFrom);
		// Successor implies ~247 (within the sanity band of 250) - a ~1.2%/3W drop, under both
		// the 2% and 5W small-drop thresholds.
		newPowerActivity(athlete, Sport.BIKE, effectiveFrom.plusDays(5).atStartOfDay(ZoneOffset.UTC).toInstant(),
				Math.round(247 / 0.95f), 1200);

		assertThat(calculator.upcomingDrop(athlete, ThresholdField.FTP, today)).isNull();
	}

	@Test
	void upcomingDropSuppressedForANearMatchInTheLast14Days() {
		// current=1000 (an unrealistic magnitude, chosen to cleanly separate rule 1's percent-
		// AND-absolute small-drop threshold from rule 2's percent-only near-match threshold): a
		// 15W/1.5% gap fails rule 1 (15W is not < the 5W absolute floor) but passes rule 2
		// (near-match only checks <=2%), isolating which rule actually suppresses it.
		User athlete = newAthlete("threshold-sug-near-match@example.cc", 1000);
		LocalDate today = LocalDate.of(2026, 6, 1);
		int lead = ThresholdHistoryCalculator.warningLeadDays(athlete);
		LocalDate effectiveFrom = today.minusDays(athlete.getThresholdWindowDays() + 1L - lead);
		newLedgerEntry(athlete, ThresholdField.FTP, 1000, null, effectiveFrom);
		newPowerActivity(athlete, Sport.BIKE, today.minusDays(2).atStartOfDay(ZoneOffset.UTC).toInstant(),
				Math.round(985 / 0.95f), 1200);

		assertThat(calculator.upcomingDrop(athlete, ThresholdField.FTP, today)).isNull();
	}

	@Test
	void upcomingDropPaceSmallDropUsesThePaceSpecificThreshold() {
		User athlete = newAthlete("threshold-sug-pace-small-drop@example.cc", null);
		LocalDate today = LocalDate.of(2026, 6, 1);
		athlete.setThresholdPace("5:00"); // 300s/km
		athlete = userRepository.save(athlete);
		int lead = ThresholdHistoryCalculator.warningLeadDays(athlete);
		LocalDate effectiveFrom = today.minusDays(athlete.getThresholdWindowDays() + 1L - lead);
		newLedgerEntry(athlete, ThresholdField.THRESHOLD_PACE, null, "5:00", effectiveFrom);
		// A 2-second/km slip (0.67%) - under both the 2% and 3s pace-specific small-drop
		// thresholds (pace uses 3s, not power fields' 5W).
		newPaceActivity(athlete, effectiveFrom.plusDays(5).atStartOfDay(ZoneOffset.UTC).toInstant(), 302, 3600);

		assertThat(calculator.upcomingDrop(athlete, ThresholdField.THRESHOLD_PACE, today)).isNull();
	}

	@Test
	void upcomingDropShowsRaceWillRefreshWhenARaceIsBooked() {
		User athlete = newAthlete("threshold-sug-race-booked@example.cc", null);
		athlete.setCriticalRunPower(250);
		athlete = userRepository.save(athlete);
		LocalDate today = LocalDate.of(2026, 6, 1);
		int lead = ThresholdHistoryCalculator.warningLeadDays(athlete);
		LocalDate effectiveFrom = today.minusDays(athlete.getThresholdWindowDays() + 1L - lead);
		newLedgerEntry(athlete, ThresholdField.CRITICAL_RUN_POWER, 250, null, effectiveFrom);
		Race race = new Race();
		race.setAthlete(athlete);
		race.setName("Local 10K");
		race.setDate(today.plusDays(5));
		race.setSport(Sport.RUN);
		race = raceRepository.save(race);

		UpcomingDropResult result = calculator.upcomingDrop(athlete, ThresholdField.CRITICAL_RUN_POWER, today);

		assertThat(result).isInstanceOf(UpcomingDropResult.RaceWillRefresh.class);
		assertThat(((UpcomingDropResult.RaceWillRefresh) result).race().getId()).isEqualTo(race.getId());
	}

	@Test
	void upcomingDropHasNullSuccessorWhenThereIsNone() {
		User athlete = newAthlete("threshold-sug-no-successor@example.cc", 250);
		LocalDate today = LocalDate.of(2026, 6, 1);
		int lead = ThresholdHistoryCalculator.warningLeadDays(athlete);
		LocalDate effectiveFrom = today.minusDays(athlete.getThresholdWindowDays() + 1L - lead);
		newLedgerEntry(athlete, ThresholdField.FTP, 250, null, effectiveFrom);

		UpcomingDropResult result = calculator.upcomingDrop(athlete, ThresholdField.FTP, today);

		assertThat(result).isInstanceOf(UpcomingDropResult.UpcomingDrop.class);
		assertThat(((UpcomingDropResult.UpcomingDrop) result).successor()).isNull();
	}
}
