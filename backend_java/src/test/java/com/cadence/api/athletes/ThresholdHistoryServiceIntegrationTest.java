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

	@Test
	void seedsTheInitialValueWhenNoEntryExists() {
		User athlete = newAthlete("manual-threshold-seed@example.cc");

		boolean changed = thresholdHistoryService.recordManualValue(athlete, ThresholdField.FTP, 250, null);

		assertThat(changed).isTrue();
		ThresholdHistory entry = thresholdHistoryRepository
				.findFirstByAthleteIdAndFieldOrderByEffectiveFromDesc(athlete.getId(), ThresholdField.FTP).orElseThrow();
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
				.findByAthleteIdAndFieldOrderByEffectiveFromDesc(athlete.getId(), ThresholdField.FTP)).hasSize(1);
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
				.findByAthleteIdAndFieldOrderByEffectiveFromDesc(athlete.getId(), ThresholdField.FTP);
		assertThat(entries).hasSize(2);
		assertThat(entries.get(0).getValueNumeric()).isEqualTo(180);
	}

	@Test
	void thresholdPaceStoresTheMmssString() {
		User athlete = newAthlete("manual-threshold-pace@example.cc");

		boolean changed = thresholdHistoryService.recordManualValue(athlete, ThresholdField.THRESHOLD_PACE, null, "4:15");

		assertThat(changed).isTrue();
		ThresholdHistory entry = thresholdHistoryRepository
				.findFirstByAthleteIdAndFieldOrderByEffectiveFromDesc(athlete.getId(), ThresholdField.THRESHOLD_PACE).orElseThrow();
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
}
