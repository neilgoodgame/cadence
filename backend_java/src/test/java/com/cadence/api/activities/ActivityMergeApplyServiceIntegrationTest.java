package com.cadence.api.activities;

import static org.assertj.core.api.Assertions.assertThat;

import com.cadence.api.activities.ActivityMergeApplyService.FieldRewriteCounts;
import com.cadence.api.activities.ActivityMergeApplyService.NullPolicy;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.support.IntegrationTest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Issue #501 (phase 1, part 2): mirrors backend's activities/tests/test_merge_apply.py. */
class ActivityMergeApplyServiceIntegrationTest extends IntegrationTest {

	private static final Instant START = Instant.parse("2026-06-01T18:00:00Z");

	@Autowired
	private ActivityMergeApplyService mergeApplyService;
	@Autowired
	private ActivityRepository activityRepository;
	@Autowired
	private RecordRepository recordRepository;
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

	private void addRecords(Activity activity, int count, BiConsumer<Record, Integer> customize) {
		List<Record> records = new ArrayList<>();
		for (int t = 0; t < count; t++) {
			Record record = new Record();
			record.setId(new RecordId(activity.getId(), START.plusSeconds(t)));
			record.setActivity(activity);
			record.setT(t);
			customize.accept(record, t);
			records.add(record);
		}
		recordRepository.saveAll(records);
	}

	@Test
	void keepOriginalPreservesTargetWhereSourceIsNull() {
		User athlete = newAthlete("merge-apply-keep-original@example.cc");
		Activity target = newActivity(athlete, "Target");
		Activity source = newActivity(athlete, "Source");
		addRecords(target, 10, (r, t) -> r.setPower(100 + t));
		// Source only has power for t in [0, 5) - the rest stays null.
		addRecords(source, 5, (r, t) -> r.setPower(200 + t));

		Map<String, FieldRewriteCounts> counts = mergeApplyService.applyMergeFields(
				target.getId(), source.getId(), List.of("power"), NullPolicy.KEEP_ORIGINAL, 0);

		Map<Integer, Integer> rows = powerByT(target);
		assertThat(rows).containsEntry(0, 200).containsEntry(4, 204).containsEntry(5, 105).containsEntry(9, 109);
		assertThat(counts.get("power").copied()).isEqualTo(5);
		assertThat(counts.get("power").keptOriginal()).isEqualTo(5);
		assertThat(counts.get("power").nulled()).isEqualTo(0);
	}

	@Test
	void overwriteNullsTargetWhereSourceIsNull() {
		User athlete = newAthlete("merge-apply-overwrite@example.cc");
		Activity target = newActivity(athlete, "Target");
		Activity source = newActivity(athlete, "Source");
		addRecords(target, 10, (r, t) -> r.setPower(100 + t));
		addRecords(source, 5, (r, t) -> r.setPower(200 + t));

		Map<String, FieldRewriteCounts> counts = mergeApplyService.applyMergeFields(
				target.getId(), source.getId(), List.of("power"), NullPolicy.OVERWRITE, 0);

		Map<Integer, Integer> rows = powerByT(target);
		assertThat(rows.get(4)).isEqualTo(204);
		assertThat(rows.get(5)).isNull();
		assertThat(rows.get(9)).isNull();
		assertThat(counts.get("power").copied()).isEqualTo(5);
		assertThat(counts.get("power").nulled()).isEqualTo(5);
	}

	@Test
	void offsetShiftsWhichSourceSecondIsUsed() {
		// offsetSeconds = 3 means the source started 3s later than the target, so target's t
		// pairs with source's (t - 3).
		User athlete = newAthlete("merge-apply-offset@example.cc");
		Activity target = newActivity(athlete, "Target");
		Activity source = newActivity(athlete, "Source");
		addRecords(target, 10, (r, t) -> r.setPower(100 + t));
		addRecords(source, 10, (r, t) -> r.setPower(900 + t));

		mergeApplyService.applyMergeFields(target.getId(), source.getId(), List.of("power"), NullPolicy.OVERWRITE, 3);

		Map<Integer, Integer> rows = powerByT(target);
		assertThat(rows.get(3)).isEqualTo(900);
		assertThat(rows.get(9)).isEqualTo(906);
		assertThat(rows.get(0)).isNull();
		assertThat(rows.get(2)).isNull();
	}

	@Test
	void copiesOnlyTheTargetTimelineNeverCreatesNewRows() {
		User athlete = newAthlete("merge-apply-timeline@example.cc");
		Activity target = newActivity(athlete, "Target");
		Activity source = newActivity(athlete, "Source");
		addRecords(target, 5, (r, t) -> r.setPower(100 + t));
		addRecords(source, 20, (r, t) -> r.setPower(900 + t));

		mergeApplyService.applyMergeFields(target.getId(), source.getId(), List.of("power"), NullPolicy.OVERWRITE, 0);

		List<Record> targetRecords = recordRepository.findByActivityIdOrderByT(target.getId());
		assertThat(targetRecords).hasSize(5);
		assertThat(targetRecords).extracting(Record::getT).containsExactly(0, 1, 2, 3, 4);
	}

	@Test
	void onlySelectedFieldsAreTouched() {
		User athlete = newAthlete("merge-apply-selected@example.cc");
		Activity target = newActivity(athlete, "Target");
		Activity source = newActivity(athlete, "Source");
		addRecords(target, 5, (r, t) -> {
			r.setPower(100 + t);
			r.setHeartrate(140 + t);
		});
		addRecords(source, 5, (r, t) -> {
			r.setPower(900 + t);
			r.setHeartrate(180 + t);
		});

		mergeApplyService.applyMergeFields(target.getId(), source.getId(), List.of("power"), NullPolicy.OVERWRITE, 0);

		List<Record> targetRecords = recordRepository.findByActivityIdOrderByT(target.getId());
		assertThat(targetRecords).extracting(Record::getHeartrate).containsExactly(140, 141, 142, 143, 144);
	}

	@Test
	void activityLevelFieldsAreCopiedDirectly() {
		User athlete = newAthlete("merge-apply-activity-fields@example.cc");
		Activity target = newActivity(athlete, "Bike on 2026-06-01");
		target.setFluidsMl(null);
		activityRepository.save(target);
		Activity source = newActivity(athlete, "Zwift - Mt Fuji");
		source.setFluidsMl(750);
		activityRepository.save(source);

		Map<String, FieldRewriteCounts> counts = mergeApplyService.applyMergeFields(
				target.getId(), source.getId(), List.of("name", "fluids_ml"), NullPolicy.KEEP_ORIGINAL, 0);

		Activity reloaded = activityRepository.findById(target.getId()).orElseThrow();
		assertThat(reloaded.getName()).isEqualTo("Zwift - Mt Fuji");
		assertThat(reloaded.getFluidsMl()).isEqualTo(750);
		assertThat(counts.get("name").copied()).isEqualTo(1);
		assertThat(counts.get("fluids_ml").copied()).isEqualTo(1);
	}

	@Test
	void activityLevelFieldNullOnSourceIsCountedAsNulledNotCopied() {
		User athlete = newAthlete("merge-apply-activity-null@example.cc");
		Activity target = newActivity(athlete, "Target");
		target.setFluidsMl(500);
		activityRepository.save(target);
		Activity source = newActivity(athlete, "Source");

		Map<String, FieldRewriteCounts> counts = mergeApplyService.applyMergeFields(
				target.getId(), source.getId(), List.of("fluids_ml"), NullPolicy.KEEP_ORIGINAL, 0);

		Activity reloaded = activityRepository.findById(target.getId()).orElseThrow();
		assertThat(reloaded.getFluidsMl()).isNull();
		assertThat(counts.get("fluids_ml").nulled()).isEqualTo(1);
		assertThat(counts.get("fluids_ml").copied()).isEqualTo(0);
	}

	@Test
	void previewMatchesApplyButWritesNothing() {
		User athlete = newAthlete("merge-apply-preview-matches@example.cc");
		Activity target = newActivity(athlete, "Target");
		Activity source = newActivity(athlete, "Source");
		source.setFluidsMl(750);
		activityRepository.save(source);
		addRecords(target, 10, (r, t) -> r.setPower(100 + t));
		addRecords(source, 5, (r, t) -> r.setPower(200 + t));

		Map<String, FieldRewriteCounts> previewCounts = mergeApplyService.previewMergeFields(
				target.getId(), source.getId(), List.of("power", "fluids_ml"), NullPolicy.KEEP_ORIGINAL, 0);
		Map<String, FieldRewriteCounts> applyCounts = mergeApplyService.applyMergeFields(
				target.getId(), source.getId(), List.of("power", "fluids_ml"), NullPolicy.KEEP_ORIGINAL, 0);

		assertThat(previewCounts).isEqualTo(applyCounts);
	}

	@Test
	void previewWritesNothingAtAll() {
		User athlete = newAthlete("merge-apply-preview-noop@example.cc");
		Activity target = newActivity(athlete, "Target");
		target.setFluidsMl(null);
		activityRepository.save(target);
		Activity source = newActivity(athlete, "Source");
		source.setFluidsMl(750);
		activityRepository.save(source);
		addRecords(target, 5, (r, t) -> r.setPower(100 + t));
		addRecords(source, 5, (r, t) -> r.setPower(900 + t));

		mergeApplyService.previewMergeFields(target.getId(), source.getId(), List.of("power", "fluids_ml"), NullPolicy.OVERWRITE, 0);

		Activity reloaded = activityRepository.findById(target.getId()).orElseThrow();
		assertThat(reloaded.getFluidsMl()).isNull();
		List<Record> targetRecords = recordRepository.findByActivityIdOrderByT(target.getId());
		assertThat(targetRecords).extracting(Record::getPower).containsExactly(100, 101, 102, 103, 104);
	}

	@Test
	void unrecognizedFieldNamesAreSilentlyIgnored() {
		User athlete = newAthlete("merge-apply-unknown-field@example.cc");
		Activity target = newActivity(athlete, "Target");
		Activity source = newActivity(athlete, "Source");

		Map<String, FieldRewriteCounts> counts = mergeApplyService.applyMergeFields(
				target.getId(), source.getId(), List.of("not_a_real_field"), NullPolicy.OVERWRITE, 0);

		assertThat(counts).isEmpty();
	}

	private Map<Integer, Integer> powerByT(Activity activity) {
		Map<Integer, Integer> result = new java.util.HashMap<>();
		for (Record record : recordRepository.findByActivityIdOrderByT(activity.getId())) {
			result.put(record.getT(), record.getPower());
		}
		return result;
	}
}
