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
}
