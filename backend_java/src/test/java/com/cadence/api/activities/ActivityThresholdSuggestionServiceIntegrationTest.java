package com.cadence.api.activities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cadence.api.common.domain.Sport;
import com.cadence.api.common.error.ConflictException;
import com.cadence.api.common.error.ValidationException;
import com.cadence.api.support.IntegrationTest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ActivityThresholdSuggestionServiceIntegrationTest extends IntegrationTest {

	@Autowired
	private ActivityThresholdSuggestionService service;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private ActivityRepository activityRepository;
	@Autowired
	private RecordRepository recordRepository;

	private User newAthlete(String email, Integer ftp) {
		User user = new User();
		user.setEmail(email);
		user.setName("Test Athlete");
		user.setPassword("irrelevant-for-this-test");
		user.setFtp(ftp);
		return userRepository.save(user);
	}

	private Activity newBikeActivityWithRecords(User athlete, int ftpSnapshot, int suggestedFtp) {
		Activity activity = new Activity();
		activity.setAthlete(athlete);
		activity.setSport(Sport.BIKE);
		activity.setName("Old ride");
		activity.setStartDate(Instant.parse("2026-01-01T07:00:00Z"));
		activity.setMovingTime(3600);
		activity.setFtpSnapshot(ftpSnapshot);
		activity.setSuggestedFtp(suggestedFtp);
		activity.setTss(0);
		activity = activityRepository.save(activity);

		for (int t = 0; t < 3600; t++) {
			Record record = new Record();
			record.setId(new RecordId(activity.getId(), Instant.parse("2026-01-01T07:00:00Z").plusSeconds(t)));
			record.setActivity(activity);
			record.setT(t);
			record.setPower(suggestedFtp);
			recordRepository.save(record);
		}
		return activity;
	}

	@Test
	void acceptingUpdatesProfileAndThisActivitysOwnSnapshotAndRecomputesTss() {
		User athlete = newAthlete("threshold-suggestion-accept@example.cc", 200);
		Activity activity = newBikeActivityWithRecords(athlete, 200, 260);

		Activity updated = service.apply(activity.getId(), "ftp", true);

		assertThat(updated.getFtpSnapshot()).isEqualTo(260);
		assertThat(updated.getSuggestedFtp()).isNull();
		// 260W at a newly-accepted 260W FTP for a full hour = 100 TSS.
		assertThat(updated.getTss()).isEqualTo(100);

		User reloadedAthlete = userRepository.findById(athlete.getId()).orElseThrow();
		assertThat(reloadedAthlete.getFtp()).isEqualTo(260);
	}

	@Test
	void dismissingClearsTheSuggestionWithoutTouchingProfileOrSnapshot() {
		User athlete = newAthlete("threshold-suggestion-dismiss@example.cc", 200);
		Activity activity = newBikeActivityWithRecords(athlete, 200, 260);

		Activity updated = service.apply(activity.getId(), "ftp", false);

		assertThat(updated.getSuggestedFtp()).isNull();
		assertThat(updated.getFtpSnapshot()).isEqualTo(200); // unchanged

		User reloadedAthlete = userRepository.findById(athlete.getId()).orElseThrow();
		assertThat(reloadedAthlete.getFtp()).isEqualTo(200); // unchanged
	}

	@Test
	void noPendingSuggestionThrowsConflict() {
		User athlete = newAthlete("threshold-suggestion-none@example.cc", 200);
		Activity activity = new Activity();
		activity.setAthlete(athlete);
		activity.setSport(Sport.BIKE);
		activity.setName("No suggestion");
		activity.setStartDate(Instant.parse("2026-01-01T07:00:00Z"));
		activity.setFtpSnapshot(200);
		activity = activityRepository.save(activity);

		String id = activity.getId();
		assertThatThrownBy(() -> service.apply(id, "ftp", true)).isInstanceOf(ConflictException.class);
	}

	@Test
	void acceptingThresholdPaceDoesNotTouchTssOrIntensity() {
		// Neither TSS nor intensity is derived from pace anywhere in this codebase - accepting
		// a pace suggestion should only touch the profile + this activity's own pace snapshot.
		User athlete = newAthlete("threshold-suggestion-pace@example.cc", null);
		athlete.setThresholdPace("4:30");
		athlete = userRepository.save(athlete);

		Activity activity = new Activity();
		activity.setAthlete(athlete);
		activity.setSport(Sport.RUN);
		activity.setName("Run");
		activity.setStartDate(Instant.parse("2026-01-01T07:00:00Z"));
		activity.setThresholdPaceSnapshot("4:30");
		activity.setSuggestedThresholdPace("4:00");
		activity.setTss(42);
		activity = activityRepository.save(activity);

		Activity updated = service.apply(activity.getId(), "threshold_pace", true);

		assertThat(updated.getThresholdPaceSnapshot()).isEqualTo("4:00");
		assertThat(updated.getSuggestedThresholdPace()).isEqualTo("");
		assertThat(updated.getTss()).isEqualTo(42); // unchanged

		User reloadedAthlete = userRepository.findById(athlete.getId()).orElseThrow();
		assertThat(reloadedAthlete.getThresholdPace()).isEqualTo("4:00");
	}

	@Test
	void unknownFieldIsRejected() {
		User athlete = newAthlete("threshold-suggestion-unknown@example.cc", 200);
		Activity activity = newBikeActivityWithRecords(athlete, 200, 260);
		String id = activity.getId();
		assertThatThrownBy(() -> service.apply(id, "lthr", true)).isInstanceOf(ValidationException.class);
	}
}
