package com.cadence.api.activities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cadence.api.activities.ActivityMergeApplyService.NullPolicy;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.common.error.ValidationException;
import com.cadence.api.support.IntegrationTest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Issue #501 (phase 1, part 4): mirrors backend's activities/tests/test_merge_service.py. */
class ActivityMergeServiceIntegrationTest extends IntegrationTest {

	private static final Instant START = Instant.parse("2026-06-01T18:00:00Z");

	@Autowired
	private ActivityMergeService mergeService;
	@Autowired
	private ActivityRepository activityRepository;
	@Autowired
	private RecordRepository recordRepository;
	@Autowired
	private MergeRepository mergeRepository;
	@Autowired
	private UserRepository userRepository;

	private User newAthlete(String email) {
		User user = new User();
		user.setEmail(email);
		user.setName("Athlete");
		user.setPassword("irrelevant-for-this-test");
		return userRepository.save(user);
	}

	private Activity newActivity(User athlete, String name) {
		Activity activity = new Activity();
		activity.setAthlete(athlete);
		activity.setSport(Sport.BIKE);
		activity.setName(name);
		activity.setStartDate(START);
		return activityRepository.save(activity);
	}

	private void addRecords(Activity activity, int count, int power) {
		List<Record> records = new ArrayList<>();
		for (int t = 0; t < count; t++) {
			Record record = new Record();
			record.setId(new RecordId(activity.getId(), START.plusSeconds(t)));
			record.setActivity(activity);
			record.setT(t);
			record.setPower(power);
			records.add(record);
		}
		recordRepository.saveAll(records);
	}

	@Test
	void mergesAndRecordsAnAuditRow() {
		User athlete = newAthlete("merge-service-audit@example.cc");
		Activity target = newActivity(athlete, "Target");
		Activity source = newActivity(athlete, "Source");
		addRecords(target, 10, 100);
		addRecords(source, 10, 250);

		ActivityMergeService.MergeResult result = mergeService.runMerge(target.getId(), source.getId(),
				List.of("power"), NullPolicy.KEEP_ORIGINAL, 0, false, false);

		assertThat(result.mergeId()).isNotNull();
		assertThat(result.offsetSeconds()).isZero();
		assertThat(result.fieldCounts().get("power").copied()).isEqualTo(10);
		assertThat(result.after().avgPower()).isEqualTo(250);

		assertThat(mergeRepository.findByTargetActivityId(target.getId())).hasSize(1);
		Merge merge = mergeRepository.findById(result.mergeId()).orElseThrow();
		assertThat(merge.getTargetActivity().getId()).isEqualTo(target.getId());
		assertThat(merge.getSourceActivity().getId()).isEqualTo(source.getId());

		Activity reloaded = activityRepository.findById(target.getId()).orElseThrow();
		assertThat(reloaded.getAvgPower()).isEqualTo(250);
	}

	@Test
	void previewHasNoSideEffects() {
		User athlete = newAthlete("merge-service-preview@example.cc");
		Activity target = newActivity(athlete, "Target");
		Activity source = newActivity(athlete, "Source");
		addRecords(target, 10, 100);
		addRecords(source, 10, 250);

		// See MergePreviewRollbackSignal - runMerge(preview=true) never returns normally; it
		// throws this to force its own transaction to roll back, carrying the already-computed
		// result. ActivityMergeController catches it the same way.
		ActivityMergeService.MergeResult result;
		try {
			mergeService.runMerge(target.getId(), source.getId(), List.of("power"), NullPolicy.KEEP_ORIGINAL, 0,
					false, true);
			throw new AssertionError("Expected a preview to throw MergePreviewRollbackSignal.");
		}
		catch (MergePreviewRollbackSignal signal) {
			result = signal.result();
		}

		assertThat(result.mergeId()).isNull();
		assertThat(result.after().avgPower()).isEqualTo(250); // the preview still reports the would-be outcome

		assertThat(mergeRepository.findByTargetActivityId(target.getId())).isEmpty();
		Activity reloaded = activityRepository.findById(target.getId()).orElseThrow();
		assertThat(reloaded.getAvgPower()).isNull(); // nothing actually written
		List<Record> records = recordRepository.findByActivityIdOrderByT(target.getId());
		assertThat(records).extracting(Record::getPower).containsOnly(100);
	}

	@Test
	void hidesSourceAsADuplicateWhenRequested() {
		User athlete = newAthlete("merge-service-hide@example.cc");
		Activity target = newActivity(athlete, "Target");
		Activity source = newActivity(athlete, "Source");
		addRecords(target, 10, 100);
		addRecords(source, 10, 250);

		ActivityMergeService.MergeResult result = mergeService.runMerge(target.getId(), source.getId(),
				List.of("power"), NullPolicy.KEEP_ORIGINAL, 0, true, false);

		assertThat(result.sourceHidden()).isTrue();
		Activity reloadedSource = activityRepository.findById(source.getId()).orElseThrow();
		assertThat(reloadedSource.getPrimaryActivity().getId()).isEqualTo(target.getId());
	}

	@Test
	void mergesWhenTheSourceIsAlreadyHiddenUnderThisTargetWithoutRecordingItAsHidden() {
		User athlete = newAthlete("merge-service-already-hidden@example.cc");
		Activity target = newActivity(athlete, "Target");
		Activity source = newActivity(athlete, "Source");
		addRecords(target, 10, 100);
		addRecords(source, 10, 250);
		source.setPrimaryActivity(target); // e.g. auto-marked at import by on_overlap=mark
		activityRepository.save(source);

		ActivityMergeService.MergeResult result = mergeService.runMerge(target.getId(), source.getId(),
				List.of("power"), NullPolicy.KEEP_ORIGINAL, 0, true, false);

		assertThat(result.after().avgPower()).isEqualTo(250);
		assertThat(activityRepository.findById(source.getId()).orElseThrow().getPrimaryActivity().getId())
				.isEqualTo(target.getId());
		// The merge didn't hide anything itself, so it must not claim to have - otherwise undoing
		// it would un-hide a duplicate the athlete marked separately.
		assertThat(mergeRepository.findById(result.mergeId()).orElseThrow().isSourceHidden()).isFalse();
	}

	@Test
	void stillRejectsHidingASourceThatIsADuplicateOfADifferentActivity() {
		User athlete = newAthlete("merge-service-hidden-elsewhere@example.cc");
		Activity target = newActivity(athlete, "Target");
		Activity other = newActivity(athlete, "Other primary");
		Activity source = newActivity(athlete, "Source");
		source.setPrimaryActivity(other);
		activityRepository.save(source);

		assertThatThrownBy(() -> mergeService.runMerge(target.getId(), source.getId(), List.of("power"),
				NullPolicy.KEEP_ORIGINAL, 0, true, false)).isInstanceOf(ValidationException.class);
	}

	@Test
	void rejectsAnUnrecognizedField() {
		User athlete = newAthlete("merge-service-unknown-field@example.cc");
		Activity target = newActivity(athlete, "Target");
		Activity source = newActivity(athlete, "Source");

		assertThatThrownBy(() -> mergeService.runMerge(target.getId(), source.getId(), List.of("not_a_real_field"),
				NullPolicy.OVERWRITE, 0, false, false)).isInstanceOf(ValidationException.class);
	}

	@Test
	void rejectsMergingAnActivityWithItself() {
		User athlete = newAthlete("merge-service-self@example.cc");
		Activity target = newActivity(athlete, "Target");

		assertThatThrownBy(() -> mergeService.runMerge(target.getId(), target.getId(), List.of("power"),
				NullPolicy.OVERWRITE, 0, false, false)).isInstanceOf(ValidationException.class);
	}

	@Test
	void rejectsASourceBelongingToAnotherAthlete() {
		User athlete = newAthlete("merge-service-owner@example.cc");
		User outsider = newAthlete("merge-service-outsider@example.cc");
		Activity target = newActivity(athlete, "Target");
		Activity othersActivity = newActivity(outsider, "Not yours");

		assertThatThrownBy(() -> mergeService.runMerge(target.getId(), othersActivity.getId(), List.of("power"),
				NullPolicy.OVERWRITE, 0, false, false)).isInstanceOf(ValidationException.class);
	}
}
