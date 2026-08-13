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

	private static final Instant RECENT_START_DATE = Instant.parse("2026-01-01T07:00:00Z");
	private static final Instant OLD_START_DATE = Instant.parse("2020-01-01T07:00:00Z");

	private Activity newBikeActivityWithRecords(User athlete, int ftpSnapshot, int suggestedFtp) {
		return newBikeActivityWithRecords(athlete, ftpSnapshot, suggestedFtp, RECENT_START_DATE);
	}

	private Activity newBikeActivityWithRecords(User athlete, int ftpSnapshot, int suggestedFtp, Instant startDate) {
		Activity activity = new Activity();
		activity.setAthlete(athlete);
		activity.setSport(Sport.BIKE);
		activity.setName("Old ride");
		activity.setStartDate(startDate);
		activity.setMovingTime(3600);
		activity.setFtpSnapshot(ftpSnapshot);
		activity.setSuggestedFtp(suggestedFtp);
		activity.setTss(0);
		activity = activityRepository.save(activity);

		for (int t = 0; t < 3600; t++) {
			Record record = new Record();
			record.setId(new RecordId(activity.getId(), startDate.plusSeconds(t)));
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

		Activity updated = service.apply(activity.getId(), "ftp", true, true);

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

		Activity updated = service.apply(activity.getId(), "ftp", false, false);

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
		activity.setStartDate(RECENT_START_DATE);
		activity.setFtpSnapshot(200);
		activity = activityRepository.save(activity);

		String id = activity.getId();
		assertThatThrownBy(() -> service.apply(id, "ftp", true, true)).isInstanceOf(ConflictException.class);
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
		activity.setStartDate(RECENT_START_DATE);
		activity.setThresholdPaceSnapshot("4:30");
		activity.setSuggestedThresholdPace("4:00");
		activity.setTss(42);
		activity = activityRepository.save(activity);

		Activity updated = service.apply(activity.getId(), "threshold_pace", true, true);

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
		assertThatThrownBy(() -> service.apply(id, "lthr", true, true)).isInstanceOf(ValidationException.class);
	}

	@Test
	void updateProfileFalseOnlyUpdatesTheActivitySnapshot() {
		// Old enough that updateProfile=true would be rejected (see the test below) - but
		// updateProfile=false should work regardless of age, since it never touches the profile.
		User athlete = newAthlete("threshold-suggestion-activity-only@example.cc", 200);
		Activity activity = newBikeActivityWithRecords(athlete, 200, 260, OLD_START_DATE);

		Activity updated = service.apply(activity.getId(), "ftp", true, false);

		assertThat(updated.getFtpSnapshot()).isEqualTo(260); // this activity's own snapshot still updates
		assertThat(updated.getSuggestedFtp()).isNull();
		// 260W at a 260W (newly-accepted) snapshot FTP for a full hour = 100 TSS - still
		// recomputed even though the profile wasn't touched.
		assertThat(updated.getTss()).isEqualTo(100);

		User reloadedAthlete = userRepository.findById(athlete.getId()).orElseThrow();
		assertThat(reloadedAthlete.getFtp()).isEqualTo(200); // unchanged
	}

	@Test
	void updateProfileTrueRejectedForOldActivity() {
		User athlete = newAthlete("threshold-suggestion-too-old@example.cc", 200);
		Activity activity = newBikeActivityWithRecords(athlete, 200, 260, OLD_START_DATE);
		String id = activity.getId();

		assertThatThrownBy(() -> service.apply(id, "ftp", true, true)).isInstanceOf(ValidationException.class);

		// Nothing mutated - the age check runs before any write.
		User reloadedAthlete = userRepository.findById(athlete.getId()).orElseThrow();
		assertThat(reloadedAthlete.getFtp()).isEqualTo(200);
		Activity reloadedActivity = activityRepository.findById(id).orElseThrow();
		assertThat(reloadedActivity.getFtpSnapshot()).isEqualTo(200);
		assertThat(reloadedActivity.getSuggestedFtp()).isEqualTo(260);
	}

	private Activity newBikeActivityWithRecordsAtPower(User athlete, int ftpSnapshot, int power) {
		Activity activity = new Activity();
		activity.setAthlete(athlete);
		activity.setSport(Sport.BIKE);
		activity.setName("Legacy ride");
		activity.setStartDate(Instant.parse("2026-01-01T07:00:00Z"));
		activity.setMovingTime(1200);
		activity.setFtpSnapshot(ftpSnapshot);
		activity.setThresholdChecked(false);
		activity = activityRepository.save(activity);

		for (int t = 0; t < 1200; t++) {
			Record record = new Record();
			record.setId(new RecordId(activity.getId(), Instant.parse("2026-01-01T07:00:00Z").plusSeconds(t)));
			record.setActivity(activity);
			record.setT(t);
			record.setPower(power);
			recordRepository.save(record);
		}
		return activity;
	}

	@Test
	void recomputeThresholdsFindsASuggestionOnANeverCheckedActivity() {
		// 300W for the full 20-minute window implies FTP = round(0.95 * 300) = 285, well above
		// the 200 on record.
		User athlete = newAthlete("recompute-thresholds-found@example.cc", 200);
		Activity activity = newBikeActivityWithRecordsAtPower(athlete, 200, 300);
		assertThat(activity.isThresholdChecked()).isFalse();

		Activity updated = service.recomputeThresholds(activity.getId());

		assertThat(updated.isThresholdChecked()).isTrue();
		assertThat(updated.getSuggestedFtp()).isEqualTo(285);
	}

	@Test
	void recomputeThresholdsStillFlipsTheFlagWhenNothingIsFound() {
		// 150W never exceeds the 200W already on record - no suggestion, but "checked" still
		// flips true, distinguishing "checked, found nothing" from "never checked."
		User athlete = newAthlete("recompute-thresholds-nothing@example.cc", 200);
		Activity activity = newBikeActivityWithRecordsAtPower(athlete, 200, 150);

		Activity updated = service.recomputeThresholds(activity.getId());

		assertThat(updated.isThresholdChecked()).isTrue();
		assertThat(updated.getSuggestedFtp()).isNull();
	}
}
