package com.cadence.api.athletes;

import static org.assertj.core.api.Assertions.assertThat;

import com.cadence.api.activities.Activity;
import com.cadence.api.activities.ActivityRepository;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.support.IntegrationTest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** referenceFor's activity-scoping: bike_power/run_power/pace look up the ThresholdHistory
 * ledger entry that was actually recorded current as of the activity's own date when given one,
 * instead of the athlete's current (possibly since-changed) profile. */
class ZoneServiceIntegrationTest extends IntegrationTest {

	@Autowired
	private ZoneService zoneService;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private ActivityRepository activityRepository;
	@Autowired
	private ThresholdHistoryRepository thresholdHistoryRepository;

	private User newAthlete(String email) {
		User user = new User();
		user.setEmail(email);
		user.setName("Test Athlete");
		user.setPassword("irrelevant-for-this-test");
		user.setFtp(250);
		user.setLthr(160);
		user.setThresholdPace("4:00");
		return userRepository.save(user);
	}

	private Activity newActivity(User athlete, Sport sport) {
		return newActivity(athlete, sport, Instant.parse("2026-01-01T07:00:00Z"));
	}

	private Activity newActivity(User athlete, Sport sport, Instant startDate) {
		Activity activity = new Activity();
		activity.setAthlete(athlete);
		activity.setSport(sport);
		activity.setName("Old activity");
		activity.setStartDate(startDate);
		return activityRepository.save(activity);
	}

	@Test
	void withoutActivityReadsTheAthletesLiveProfile() {
		User athlete = newAthlete("zone-reference-live@example.cc");
		assertThat(zoneService.referenceFor(athlete, ZoneType.BIKE_POWER)).isEqualTo(250.0);
		assertThat(zoneService.referenceFor(athlete, ZoneType.PACE)).isEqualTo(240.0); // "4:00" -> 240s
	}

	private ThresholdHistory newEntry(User athlete, ThresholdField field, Activity activity, Integer valueNumeric,
			String valuePace) {
		ThresholdHistory entry = new ThresholdHistory();
		entry.setAthlete(athlete);
		entry.setField(field);
		entry.setValueNumeric(valueNumeric);
		entry.setValuePace(valuePace != null ? valuePace : "");
		entry.setSourceActivity(activity);
		LocalDate date = activity.getStartDate().atZone(ZoneOffset.UTC).toLocalDate();
		entry.setEffectiveFrom(date);
		entry.setCurrentFrom(date);
		return thresholdHistoryRepository.save(entry);
	}

	private ThresholdHistory newEntryWithDates(User athlete, ThresholdField field, Activity activity,
			Integer valueNumeric, String valuePace, LocalDate effectiveFrom, LocalDate currentFrom) {
		ThresholdHistory entry = new ThresholdHistory();
		entry.setAthlete(athlete);
		entry.setField(field);
		entry.setValueNumeric(valueNumeric);
		entry.setValuePace(valuePace != null ? valuePace : "");
		entry.setSourceActivity(activity);
		entry.setEffectiveFrom(effectiveFrom);
		entry.setCurrentFrom(currentFrom);
		return thresholdHistoryRepository.save(entry);
	}

	@Test
	void aRowOnlyBecomesCurrentFromItsCurrentFromDateNotItsEffectiveFromDate() {
		// The exact real-world bug this field exists to fix: a worse row dated *after* a still-
		// better one (still well inside the window) must not win just because effectiveFrom <=
		// the target date trivially holds for the worse row's own activity date too.
		User athlete = newAthlete("zone-reference-cascading-expiry@example.cc");
		Activity better = newActivity(athlete, Sport.RUN, Instant.parse("2023-08-26T08:45:01Z"));
		Activity worse = newActivity(athlete, Sport.RUN, Instant.parse("2023-09-03T07:45:51Z"));
		newEntryWithDates(athlete, ThresholdField.THRESHOLD_PACE, better, null, "4:20",
				LocalDate.of(2023, 8, 26), LocalDate.of(2023, 8, 26));
		// Only becomes current on 2023-12-22, once the better entry above ages out of the window.
		newEntryWithDates(athlete, ThresholdField.THRESHOLD_PACE, worse, null, "4:26",
				LocalDate.of(2023, 9, 3), LocalDate.of(2023, 12, 22));

		assertThat(zoneService.referenceFor(athlete, ZoneType.PACE, worse)).isEqualTo(260.0); // "4:20" -> 260s, not "4:26"
	}

	@Test
	void withActivityReadsTheLedgerEntryEffectiveAtThatTime() {
		User athlete = newAthlete("zone-reference-ledger@example.cc");
		Activity activity = newActivity(athlete, Sport.BIKE);
		newEntry(athlete, ThresholdField.FTP, activity, 200, null);

		assertThat(zoneService.referenceFor(athlete, ZoneType.BIKE_POWER, activity)).isEqualTo(200.0);
		// The athlete's live profile still says 250 - proves the ledger entry, not the live value, won.
		assertThat(zoneService.referenceFor(athlete, ZoneType.BIKE_POWER)).isEqualTo(250.0);
	}

	@Test
	void paceEntryIsParsedFromMmssSameAsTheLiveField() {
		User athlete = newAthlete("zone-reference-pace@example.cc");
		Activity activity = newActivity(athlete, Sport.RUN);
		newEntry(athlete, ThresholdField.THRESHOLD_PACE, activity, null, "4:30");

		assertThat(zoneService.referenceFor(athlete, ZoneType.PACE, activity)).isEqualTo(270.0);
	}

	@Test
	void heartRateIgnoresActivityAndAlwaysReadsLive() {
		User athlete = newAthlete("zone-reference-hr@example.cc");
		Activity activity = newActivity(athlete, Sport.BIKE);
		newEntry(athlete, ThresholdField.FTP, activity, 200, null);

		assertThat(zoneService.referenceFor(athlete, ZoneType.HEART_RATE, activity)).isEqualTo(160.0);
	}

	@Test
	void noLedgerEntryReturnsNullRatherThanFallingBackToTheLiveProfile() {
		// An activity with no history entry effective at its own date (e.g. predating this
		// feature) should read as "unknown," not silently fall back to the athlete's current
		// FTP - that fallback-to-live behavior is exactly what activity-scoping exists to avoid.
		User athlete = newAthlete("zone-reference-null@example.cc");
		Activity activity = newActivity(athlete, Sport.BIKE);

		assertThat(zoneService.referenceFor(athlete, ZoneType.BIKE_POWER, activity)).isNull();
	}

	@Test
	void nullActivityFallsBackToTheTwoArgOverload() {
		User athlete = newAthlete("zone-reference-null-activity@example.cc");
		assertThat(zoneService.referenceFor(athlete, ZoneType.BIKE_POWER, null)).isEqualTo(250.0);
	}

	@Test
	void freshPaceZoneSetUsesDanielsTableNotTheGenericOne() {
		User athlete = newAthlete("zone-default-pace@example.cc");

		ZoneSet paceZones = zoneService.getOrCreate(athlete, ZoneType.PACE);

		assertThat(paceZones.getZones()).isEqualTo(ZoneService.DEFAULT_PACE_ZONES);
		assertThat(paceZones.getZones()).extracting(Zone::name)
				.containsExactly("Easy", "Marathon", "Threshold", "Interval", "Repetition");
	}

	@Test
	void freshBikePowerZoneSetStillUsesTheGenericTable() {
		User athlete = newAthlete("zone-default-bike@example.cc");

		ZoneSet bikeZones = zoneService.getOrCreate(athlete, ZoneType.BIKE_POWER);

		assertThat(bikeZones.getZones()).isEqualTo(ZoneService.DEFAULT_ZONES);
	}
}
