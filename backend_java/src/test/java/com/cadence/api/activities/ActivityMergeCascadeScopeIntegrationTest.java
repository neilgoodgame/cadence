package com.cadence.api.activities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import com.cadence.api.activities.ActivityMergeApplyService.NullPolicy;
import com.cadence.api.athletes.ThresholdField;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.common.error.ValidationException;
import com.cadence.api.support.IntegrationTest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** A merge preview must never run the athlete-wide threshold ledger/TSS cascade (it only needs the
 * target's own re-derived stats, and the merge dialog fires one preview per field/offset change);
 * a real merge rebuilds only the threshold fields the merged activities' sports can define. */
class ActivityMergeCascadeScopeIntegrationTest extends IntegrationTest {

	private static final Instant START = Instant.parse("2026-07-01T18:00:00Z");

	@Autowired
	private ActivityMergeService mergeService;
	@Autowired
	private ActivityRepository activityRepository;
	@Autowired
	private RecordRepository recordRepository;
	@Autowired
	private UserRepository userRepository;
	@MockitoSpyBean
	private ActivityService activityService;

	private User newAthlete(String email) {
		User user = new User();
		user.setEmail(email);
		user.setName("Athlete");
		user.setPassword("irrelevant-for-this-test");
		return userRepository.save(user);
	}

	private Activity newActivity(User athlete, String name, int power) {
		Activity activity = new Activity();
		activity.setAthlete(athlete);
		activity.setSport(Sport.BIKE);
		activity.setName(name);
		activity.setStartDate(START);
		activity = activityRepository.save(activity);
		List<Record> records = new ArrayList<>();
		for (int t = 0; t < 10; t++) {
			Record record = new Record();
			record.setId(new RecordId(activity.getId(), START.plusSeconds(t)));
			record.setActivity(activity);
			record.setT(t);
			record.setPower(power);
			records.add(record);
		}
		recordRepository.saveAll(records);
		return activity;
	}

	@Test
	void aPreviewNeverRunsTheAthleteWideCascade() {
		User athlete = newAthlete("merge-cascade-preview@example.cc");
		Activity target = newActivity(athlete, "Target", 100);
		Activity source = newActivity(athlete, "Source", 250);

		// A preview always ends by throwing its rollback signal - that's how it discards its writes.
		assertThatThrownBy(() -> mergeService.runMerge(target.getId(), source.getId(), List.of("power"),
				NullPolicy.KEEP_ORIGINAL, 0, false, true)).isInstanceOf(MergePreviewRollbackSignal.class);

		verify(activityService, never()).recomputeAfterDuplicateChange(argThat(a -> a.getId().equals(athlete.getId())));
		verify(activityService, never()).recomputeAfterDuplicateChange(
				argThat(a -> a.getId().equals(athlete.getId())), org.mockito.ArgumentMatchers.anySet());
	}

	@Test
	void aRealBikeMergeOnlyRebuildsTheBikeThresholdField() {
		User athlete = newAthlete("merge-cascade-real@example.cc");
		Activity target = newActivity(athlete, "Target", 100);
		Activity source = newActivity(athlete, "Source", 250);

		mergeService.runMerge(target.getId(), source.getId(), List.of("power"), NullPolicy.KEEP_ORIGINAL, 0, false,
				false);

		verify(activityService, times(1)).recomputeAfterDuplicateChange(
				argThat(a -> a.getId().equals(athlete.getId())),
				argThat(fields -> fields.equals(EnumSet.of(ThresholdField.FTP))));
	}
}
