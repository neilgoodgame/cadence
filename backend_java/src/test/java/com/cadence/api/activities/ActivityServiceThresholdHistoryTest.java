package com.cadence.api.activities;

import static org.assertj.core.api.Assertions.assertThat;

import com.cadence.api.activities.dto.ActivityResponse;
import com.cadence.api.activities.dto.ActivityThresholdHistoryEntry;
import com.cadence.api.athletes.ThresholdField;
import com.cadence.api.athletes.ThresholdHistory;
import com.cadence.api.athletes.ThresholdHistoryRepository;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.support.IntegrationTest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** ActivityResponse.threshold_history's two distinct signals - own effort vs. revealed by this
 * activity's own ingest pass - see ActivityService.thresholdHistoryFor. */
class ActivityServiceThresholdHistoryTest extends IntegrationTest {

	@Autowired
	private ActivityService activityService;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private ActivityRepository activityRepository;
	@Autowired
	private ThresholdHistoryRepository thresholdHistoryRepository;

	private User newAthlete(String email) {
		User user = new User();
		user.setEmail(email);
		user.setName("Athlete");
		user.setPassword("irrelevant-for-this-test");
		return userRepository.save(user);
	}

	private Activity newActivity(User athlete, String name, Instant startDate) {
		Activity activity = new Activity();
		activity.setAthlete(athlete);
		activity.setSport(Sport.RUN);
		activity.setName(name);
		activity.setStartDate(startDate);
		return activityRepository.save(activity);
	}

	private void newEntry(User athlete, ThresholdField field, Activity source, String valuePace,
			LocalDate effectiveFrom, LocalDate currentFrom) {
		ThresholdHistory entry = new ThresholdHistory();
		entry.setAthlete(athlete);
		entry.setField(field);
		entry.setValuePace(valuePace);
		entry.setSourceActivity(source);
		entry.setEffectiveFrom(effectiveFrom);
		entry.setCurrentFrom(currentFrom);
		thresholdHistoryRepository.save(entry);
	}

	@Test
	void ownEffortEntryIsFlaggedWithItsOwnActivityId() {
		User athlete = newAthlete("threshold-field-own@example.cc");
		Activity activity = newActivity(athlete, "Ride", Instant.parse("2026-01-01T07:00:00Z"));
		newEntry(athlete, ThresholdField.THRESHOLD_PACE, activity, "4:30", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 1));

		ActivityResponse response = activityService.toResponse(activity);

		assertThat(response.thresholdHistory()).hasSize(1);
		ActivityThresholdHistoryEntry entry = response.thresholdHistory().get(0);
		assertThat(entry.field()).isEqualTo(ThresholdField.THRESHOLD_PACE);
		assertThat(entry.isCurrent()).isTrue();
		assertThat(entry.sourceActivityId()).isEqualTo(activity.getId());
	}

	@Test
	void anActivityThatRevealsADifferentDormantEffortIsFlaggedWithThatEffortsActivityId() {
		// The exact real-world scenario currentFrom exists for: `better` stays current until it
		// ages out, and `trigger`'s own ingest is what notices `worse` has become current - even
		// though `worse` isn't `trigger`'s own effort at all.
		User athlete = newAthlete("threshold-field-revealed@example.cc");
		Activity better = newActivity(athlete, "Lee Valley", Instant.parse("2023-08-26T08:45:00Z"));
		Activity worse = newActivity(athlete, "Big Half", Instant.parse("2023-09-03T07:45:00Z"));
		Activity trigger = newActivity(athlete, "Run on 2023-12-22", Instant.parse("2023-12-22T07:00:00Z"));
		newEntry(athlete, ThresholdField.THRESHOLD_PACE, better, "4:20", LocalDate.of(2023, 8, 26), LocalDate.of(2023, 8, 26));
		newEntry(athlete, ThresholdField.THRESHOLD_PACE, worse, "4:26", LocalDate.of(2023, 9, 3), LocalDate.of(2023, 12, 22));

		ActivityResponse triggerResponse = activityService.toResponse(trigger);
		assertThat(triggerResponse.thresholdHistory()).hasSize(1);
		ActivityThresholdHistoryEntry revealed = triggerResponse.thresholdHistory().get(0);
		assertThat(revealed.field()).isEqualTo(ThresholdField.THRESHOLD_PACE);
		assertThat(revealed.value()).isEqualTo("4:26");
		assertThat(revealed.sourceActivityId()).isEqualTo(worse.getId()); // not trigger.getId()

		// The revealed activity's own page shows the same row as its own effort, not a reveal.
		ActivityResponse worseResponse = activityService.toResponse(worse);
		assertThat(worseResponse.thresholdHistory()).hasSize(1);
		assertThat(worseResponse.thresholdHistory().get(0).sourceActivityId()).isEqualTo(worse.getId());

		// `better`'s own page is unaffected - it was superseded, not a reveal trigger.
		ActivityResponse betterResponse = activityService.toResponse(better);
		assertThat(betterResponse.thresholdHistory()).hasSize(1);
		ActivityThresholdHistoryEntry betterEntry = betterResponse.thresholdHistory().get(0);
		assertThat(betterEntry.sourceActivityId()).isEqualTo(better.getId());
		assertThat(betterEntry.isCurrent()).isFalse();
	}
}
