package com.cadence.api.mcp.tools.activities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cadence.api.activities.Activity;
import com.cadence.api.activities.ActivityMergeApplyService.NullPolicy;
import com.cadence.api.activities.ActivityMergeService;
import com.cadence.api.activities.ActivityRepository;
import com.cadence.api.activities.ActivityTag;
import com.cadence.api.activities.ActivityTagRepository;
import com.cadence.api.activities.MergeRepository;
import com.cadence.api.activities.Record;
import com.cadence.api.activities.RecordId;
import com.cadence.api.activities.RecordRepository;
import com.cadence.api.activities.Tag;
import com.cadence.api.activities.TagRepository;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.common.error.ForbiddenException;
import com.cadence.api.common.error.NotFoundException;
import com.cadence.api.common.error.UnprocessableEntityException;
import com.cadence.api.common.error.ValidationException;
import com.cadence.api.gear.Bike;
import com.cadence.api.gear.BikeRepository;
import com.cadence.api.security.AuthContext;
import com.cadence.api.security.AuthContextHolder;
import com.cadence.api.support.IntegrationTest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ActivityWriteToolsTest extends IntegrationTest {

	@Autowired
	private ActivityWriteTools activityWriteTools;

	@Autowired
	private ActivityRepository activityRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private TagRepository tagRepository;

	@Autowired
	private ActivityTagRepository activityTagRepository;

	@Autowired
	private BikeRepository bikeRepository;

	@Autowired
	private RecordRepository recordRepository;

	@Autowired
	private MergeRepository mergeRepository;

	@AfterEach
	void clearAuthContext() {
		AuthContextHolder.clear();
	}

	private User newUser(String email) {
		User user = new User();
		user.setEmail(email);
		user.setName("Test User");
		user.setPassword("irrelevant-for-this-test");
		return userRepository.save(user);
	}

	private Activity newActivity(User athlete) {
		Activity activity = new Activity();
		activity.setAthlete(athlete);
		activity.setSport(Sport.RUN);
		activity.setName("Morning Run");
		activity.setStartDate(Instant.parse("2026-01-01T07:00:00Z"));
		return activityRepository.save(activity);
	}

	/** A hand-built activity with a power stream, for merge_activities/unmerge_activity tests -
	 * unlike {@link #newActivity}, bike sport and a real record stream so a merge has something
	 * to copy. Has no Upload row, so undo always hits the "no stored file" path (see
	 * ActivityUnmergeServiceIntegrationTest.throwsWhenTheTargetHasNoStoredUploadFile, tested at
	 * the service layer - this file only needs to confirm the tool wires that error through). */
	private Activity newActivityWithRecords(User athlete, Instant start, int count, int power) {
		Activity activity = new Activity();
		activity.setAthlete(athlete);
		activity.setSport(Sport.BIKE);
		activity.setName("Bike ride");
		activity.setStartDate(start);
		activity = activityRepository.save(activity);
		List<Record> records = new ArrayList<>();
		for (int t = 0; t < count; t++) {
			Record record = new Record();
			record.setId(new RecordId(activity.getId(), start.plusSeconds(t)));
			record.setActivity(activity);
			record.setT(t);
			record.setPower(power);
			records.add(record);
		}
		recordRepository.saveAll(records);
		return activity;
	}

	private Bike newBike(User athlete, int distanceKm) {
		Bike bike = new Bike();
		bike.setAthlete(athlete);
		bike.setName("Tarmac SL7");
		bike.setDistanceKm(distanceKm);
		return bikeRepository.save(bike);
	}

	private void authAs(String userId, String... scopes) {
		AuthContextHolder.set(AuthContext.self(userId, Set.of(scopes), AuthContext.CredentialKind.PERSONAL_ACCESS_TOKEN));
	}

	@Test
	void updateActivityRenamesIt() {
		User athlete = newUser("write-tool-rename-athlete@example.cc");
		Activity activity = newActivity(athlete);
		authAs(athlete.getId(), "activities:read", "activities:write");

		var response = activityWriteTools.updateActivity(activity.getId(), "The Gorby");

		assertThat(response.name()).isEqualTo("The Gorby");
		assertThat(activityRepository.findById(activity.getId()).orElseThrow().getName()).isEqualTo("The Gorby");
	}

	@Test
	void updateActivityRejectsABlankName() {
		User athlete = newUser("write-tool-rename-blank-athlete@example.cc");
		Activity activity = newActivity(athlete);
		authAs(athlete.getId(), "activities:read", "activities:write");

		assertThatThrownBy(() -> activityWriteTools.updateActivity(activity.getId(), "  "))
				.isInstanceOf(ValidationException.class);
	}

	@Test
	void updateActivityRejectsAnOutsiderWithNoShare() {
		User athlete = newUser("write-tool-rename-outsider-athlete@example.cc");
		Activity activity = newActivity(athlete);
		User outsider = newUser("write-tool-rename-outsider@example.cc");
		authAs(outsider.getId(), "activities:read", "activities:write");

		assertThatThrownBy(() -> activityWriteTools.updateActivity(activity.getId(), "Should fail"))
				.isInstanceOf(ForbiddenException.class);
	}

	@Test
	void tagActivityAttachesANewTag() {
		User athlete = newUser("write-tool-tag-athlete@example.cc");
		Activity activity = newActivity(athlete);
		authAs(athlete.getId(), "activities:read", "activities:write");

		var response = activityWriteTools.tagActivity(activity.getId(), "VO2 max");

		assertThat(response.tag().name()).isEqualTo("VO2 max");
		assertThat(response.activityId()).isEqualTo(activity.getId());
	}

	@Test
	void tagActivityIsCaseInsensitiveAndReusesTheExistingTag() {
		User athlete = newUser("write-tool-tag-reuse-athlete@example.cc");
		Activity activity = newActivity(athlete);
		authAs(athlete.getId(), "activities:read", "activities:write");
		var first = activityWriteTools.tagActivity(activity.getId(), "VO2 max");

		var second = activityWriteTools.tagActivity(activity.getId(), "vo2 max");

		assertThat(second.tag().id()).isEqualTo(first.tag().id());
	}

	@Test
	void tagActivityRejectsAnOutsiderWithNoShare() {
		User athlete = newUser("write-tool-tag-outsider-athlete@example.cc");
		Activity activity = newActivity(athlete);
		User outsider = newUser("write-tool-tag-outsider@example.cc");
		authAs(outsider.getId(), "activities:read", "activities:write");

		assertThatThrownBy(() -> activityWriteTools.tagActivity(activity.getId(), "VO2 max"))
				.isInstanceOf(ForbiddenException.class);
	}

	@Test
	void markDuplicateLinksItAndThePrimarysDuplicateListPicksItUp() {
		User athlete = newUser("write-tool-mark-duplicate-athlete@example.cc");
		Activity primary = newActivity(athlete);
		Activity duplicate = newActivity(athlete);
		authAs(athlete.getId(), "activities:read", "activities:write");

		var response = activityWriteTools.markDuplicate(duplicate.getId(), primary.getId());

		assertThat(response.primaryActivityId()).isEqualTo(primary.getId());
		assertThat(activityRepository.findById(duplicate.getId()).orElseThrow().getPrimaryActivity().getId())
				.isEqualTo(primary.getId());
	}

	@Test
	void markDuplicateRetractsItsGearMileage() {
		// Issue #498: a duplicate's distance must not double-count on its bike's mileage total
		// alongside its primary's.
		User athlete = newUser("write-tool-mark-duplicate-mileage-athlete@example.cc");
		Bike bike = newBike(athlete, 142);
		Activity primary = newActivity(athlete);
		Activity duplicate = newActivity(athlete);
		duplicate.setBike(bike);
		duplicate.setDistanceKm(42.0);
		activityRepository.save(duplicate);
		authAs(athlete.getId(), "activities:read", "activities:write");

		activityWriteTools.markDuplicate(duplicate.getId(), primary.getId());

		assertThat(bikeRepository.findById(bike.getId()).orElseThrow().getDistanceKm()).isEqualTo(100);
	}

	@Test
	void markDuplicateRejectsSelfLink() {
		User athlete = newUser("write-tool-mark-duplicate-self-athlete@example.cc");
		Activity activity = newActivity(athlete);
		authAs(athlete.getId(), "activities:read", "activities:write");

		assertThatThrownBy(() -> activityWriteTools.markDuplicate(activity.getId(), activity.getId()))
				.isInstanceOf(ValidationException.class);
	}

	@Test
	void markDuplicateRejectsAnOutsiderWithNoShare() {
		User athlete = newUser("write-tool-mark-duplicate-outsider-athlete@example.cc");
		Activity primary = newActivity(athlete);
		Activity duplicate = newActivity(athlete);
		User outsider = newUser("write-tool-mark-duplicate-outsider@example.cc");
		authAs(outsider.getId(), "activities:read", "activities:write");

		assertThatThrownBy(() -> activityWriteTools.markDuplicate(duplicate.getId(), primary.getId()))
				.isInstanceOf(ForbiddenException.class);
	}

	@Test
	void unmarkDuplicateClearsTheLink() {
		User athlete = newUser("write-tool-unmark-duplicate-athlete@example.cc");
		Activity primary = newActivity(athlete);
		Activity duplicate = newActivity(athlete);
		authAs(athlete.getId(), "activities:read", "activities:write");
		activityWriteTools.markDuplicate(duplicate.getId(), primary.getId());

		var response = activityWriteTools.unmarkDuplicate(duplicate.getId());

		assertThat(response.primaryActivityId()).isNull();
		assertThat(activityRepository.findById(duplicate.getId()).orElseThrow().getPrimaryActivity()).isNull();
	}

	@Test
	void unmarkDuplicateRestoresItsGearMileage() {
		User athlete = newUser("write-tool-unmark-duplicate-mileage-athlete@example.cc");
		Bike bike = newBike(athlete, 100);
		Activity primary = newActivity(athlete);
		Activity duplicate = newActivity(athlete);
		duplicate.setBike(bike);
		duplicate.setDistanceKm(42.0);
		activityRepository.save(duplicate);
		authAs(athlete.getId(), "activities:read", "activities:write");
		activityWriteTools.markDuplicate(duplicate.getId(), primary.getId());
		assertThat(bikeRepository.findById(bike.getId()).orElseThrow().getDistanceKm()).isEqualTo(58);

		activityWriteTools.unmarkDuplicate(duplicate.getId());

		assertThat(bikeRepository.findById(bike.getId()).orElseThrow().getDistanceKm()).isEqualTo(100);
	}

	@Test
	void unmarkDuplicateIsANoOpWhenNotADuplicate() {
		User athlete = newUser("write-tool-unmark-duplicate-noop-athlete@example.cc");
		Activity activity = newActivity(athlete);
		authAs(athlete.getId(), "activities:read", "activities:write");

		var response = activityWriteTools.unmarkDuplicate(activity.getId());

		assertThat(response.primaryActivityId()).isNull();
	}

	@Test
	void setPrimarySwapsRolesAndRepointsOtherSiblings() {
		User athlete = newUser("write-tool-set-primary-athlete@example.cc");
		Activity primary = newActivity(athlete);
		Activity duplicateA = newActivity(athlete);
		Activity duplicateB = newActivity(athlete);
		authAs(athlete.getId(), "activities:read", "activities:write");
		activityWriteTools.markDuplicate(duplicateA.getId(), primary.getId());
		duplicateB.setPrimaryActivity(primary);
		activityRepository.save(duplicateB);

		var response = activityWriteTools.setPrimary(duplicateA.getId());

		assertThat(response.primaryActivityId()).isNull();
		assertThat(response.duplicateActivityIds()).containsExactlyInAnyOrder(primary.getId(), duplicateB.getId());
		assertThat(activityRepository.findById(primary.getId()).orElseThrow().getPrimaryActivity().getId())
				.isEqualTo(duplicateA.getId());
		assertThat(activityRepository.findById(duplicateB.getId()).orElseThrow().getPrimaryActivity().getId())
				.isEqualTo(duplicateA.getId());
	}

	@Test
	void setPrimarySwapsGearMileageBetweenOldAndNewPrimary() {
		// Issue #498: promoting a duplicate to primary must restore its own gear mileage and
		// retract the (now-demoted) old primary's.
		User athlete = newUser("write-tool-set-primary-mileage-athlete@example.cc");
		Bike bike = newBike(athlete, 142);
		Activity primary = newActivity(athlete);
		primary.setBike(bike);
		primary.setDistanceKm(40.0);
		activityRepository.save(primary);
		Activity duplicate = newActivity(athlete);
		duplicate.setBike(bike);
		duplicate.setDistanceKm(42.0);
		activityRepository.save(duplicate);
		authAs(athlete.getId(), "activities:read", "activities:write");
		activityWriteTools.markDuplicate(duplicate.getId(), primary.getId());
		assertThat(bikeRepository.findById(bike.getId()).orElseThrow().getDistanceKm()).isEqualTo(100);

		activityWriteTools.setPrimary(duplicate.getId());

		assertThat(bikeRepository.findById(bike.getId()).orElseThrow().getDistanceKm()).isEqualTo(102);
	}

	@Test
	void setPrimaryRejectsAnActivityThatIsAlreadyPrimary() {
		User athlete = newUser("write-tool-set-primary-already-athlete@example.cc");
		Activity activity = newActivity(athlete);
		authAs(athlete.getId(), "activities:read", "activities:write");

		assertThatThrownBy(() -> activityWriteTools.setPrimary(activity.getId()))
				.isInstanceOf(ValidationException.class);
	}

	@Test
	void setPrimaryRejectsAnOutsiderWithNoShare() {
		User athlete = newUser("write-tool-set-primary-outsider-athlete@example.cc");
		Activity primary = newActivity(athlete);
		Activity duplicate = newActivity(athlete);
		duplicate.setPrimaryActivity(primary);
		activityRepository.save(duplicate);
		User outsider = newUser("write-tool-set-primary-outsider@example.cc");
		authAs(outsider.getId(), "activities:read", "activities:write");

		assertThatThrownBy(() -> activityWriteTools.setPrimary(duplicate.getId())).isInstanceOf(ForbiddenException.class);
	}

	@Test
	void mergeActivitiesPreviewDefaultsTrueAndWritesNothing() {
		User athlete = newUser("write-tool-merge-preview-default-athlete@example.cc");
		Instant start = Instant.parse("2026-06-01T18:00:00Z");
		Activity target = newActivityWithRecords(athlete, start, 10, 100);
		Activity source = newActivityWithRecords(athlete, start, 10, 250);
		authAs(athlete.getId(), "activities:read", "activities:write");

		// preview omitted entirely - the tool's own default (true) must apply, the opposite of
		// the REST endpoint's default (false).
		ActivityMergeService.MergeResult result = activityWriteTools.mergeActivities(
				target.getId(), source.getId(), List.of("power"), null, null, 0, null, null, null);

		assertThat(result.mergeId()).isNull();
		assertThat(result.after().avgPower()).isEqualTo(250); // the preview still reports the would-be outcome
		// Nothing was actually written - a fresh fixture's avgPower is null until some recompute
		// sets it, and preview's own recompute ran inside the transaction that then rolled back.
		assertThat(activityRepository.findById(target.getId()).orElseThrow().getAvgPower()).isNull();
		assertThat(mergeRepository.findByTargetActivityId(target.getId())).isEmpty();
	}

	@Test
	void mergeActivitiesAppliesAndRecordsAnAuditRowWhenPreviewIsFalse() {
		User athlete = newUser("write-tool-merge-apply-athlete@example.cc");
		Instant start = Instant.parse("2026-06-01T18:00:00Z");
		Activity target = newActivityWithRecords(athlete, start, 10, 100);
		Activity source = newActivityWithRecords(athlete, start, 10, 250);
		authAs(athlete.getId(), "activities:read", "activities:write");

		ActivityMergeService.MergeResult result = activityWriteTools.mergeActivities(
				target.getId(), source.getId(), List.of("power"), "overwrite", true, 0, false, null, null);

		assertThat(result.mergeId()).isNotNull();
		assertThat(result.sourceHidden()).isTrue();
		assertThat(activityRepository.findById(target.getId()).orElseThrow().getAvgPower()).isEqualTo(250);
		assertThat(activityRepository.findById(source.getId()).orElseThrow().getPrimaryActivity().getId())
				.isEqualTo(target.getId());
	}

	@Test
	void mergeActivitiesDefaultsNullsToKeepOriginal() {
		User athlete = newUser("write-tool-merge-nulls-default-athlete@example.cc");
		Instant start = Instant.parse("2026-06-01T18:00:00Z");
		Activity target = newActivityWithRecords(athlete, start, 10, 100);
		Activity source = newActivityWithRecords(athlete, start, 5, 250); // Only 5 of 10 seconds have source data.
		authAs(athlete.getId(), "activities:read", "activities:write");

		// nulls omitted - the tool's own default (keep_original) must apply.
		ActivityMergeService.MergeResult result = activityWriteTools.mergeActivities(
				target.getId(), source.getId(), List.of("power"), null, null, 0, true, null, null);

		assertThat(result.fieldCounts().get("power").keptOriginal()).isEqualTo(5);
		assertThat(result.fieldCounts().get("power").nulled()).isZero();
	}

	@Test
	void mergeActivitiesRejectsAnInvalidNullsValue() {
		User athlete = newUser("write-tool-merge-bad-nulls-athlete@example.cc");
		Instant start = Instant.parse("2026-06-01T18:00:00Z");
		Activity target = newActivityWithRecords(athlete, start, 10, 100);
		Activity source = newActivityWithRecords(athlete, start, 10, 250);
		authAs(athlete.getId(), "activities:read", "activities:write");

		assertThatThrownBy(() -> activityWriteTools.mergeActivities(
				target.getId(), source.getId(), List.of("power"), "bogus", null, 0, true, null, null))
				.isInstanceOf(ValidationException.class);
	}

	@Test
	void mergeActivitiesRejectsAnOutsiderWithNoShare() {
		User athlete = newUser("write-tool-merge-outsider-athlete@example.cc");
		Instant start = Instant.parse("2026-06-01T18:00:00Z");
		Activity target = newActivityWithRecords(athlete, start, 10, 100);
		Activity source = newActivityWithRecords(athlete, start, 10, 250);
		User outsider = newUser("write-tool-merge-outsider@example.cc");
		authAs(outsider.getId(), "activities:read", "activities:write");

		assertThatThrownBy(() -> activityWriteTools.mergeActivities(
				target.getId(), source.getId(), List.of("power"), null, null, 0, true, null, null))
				.isInstanceOf(ForbiddenException.class);
	}

	@Test
	void unmergeActivityDelegatesToTheServiceAndSurfacesItsError() {
		// No stored Upload file on this hand-built fixture, so undo can never actually complete -
		// confirms the tool wires the real failure through rather than swallowing or changing it.
		// See ActivityUnmergeServiceIntegrationTest.throwsWhenTheTargetHasNoStoredUploadFile for
		// full undo-mechanics coverage at the service layer.
		User athlete = newUser("write-tool-unmerge-no-file-athlete@example.cc");
		Instant start = Instant.parse("2026-06-01T18:00:00Z");
		Activity target = newActivityWithRecords(athlete, start, 10, 100);
		Activity source = newActivityWithRecords(athlete, start, 10, 250);
		authAs(athlete.getId(), "activities:read", "activities:write");
		activityWriteTools.mergeActivities(target.getId(), source.getId(), List.of("power"), "overwrite", null, 0, false, null, null);

		assertThatThrownBy(() -> activityWriteTools.unmergeActivity(target.getId()))
				.isInstanceOf(UnprocessableEntityException.class);
	}

	@Test
	void unmergeActivityRejectsAnOutsiderWithNoShare() {
		User athlete = newUser("write-tool-unmerge-outsider-athlete@example.cc");
		Activity target = newActivity(athlete);
		User outsider = newUser("write-tool-unmerge-outsider@example.cc");
		authAs(outsider.getId(), "activities:read", "activities:write");

		assertThatThrownBy(() -> activityWriteTools.unmergeActivity(target.getId()))
				.isInstanceOf(ForbiddenException.class);
	}

	@Test
	void setMergeRuleSavesAndReturnsIt() {
		User athlete = newUser("write-tool-set-merge-rule-athlete@example.cc");
		authAs(athlete.getId(), "activities:read", "activities:write");

		var response = activityWriteTools.setMergeRule(
				"run", "device:coros", "device:garmin", List.of("heartrate"), "overwrite");

		assertThat(response.get("sport")).isEqualTo("run");
		assertThat(response.get("target_pattern")).isEqualTo("device:coros");
		assertThat(response.get("source_pattern")).isEqualTo("device:garmin");
		assertThat(response.get("fields")).isEqualTo(List.of("heartrate"));
		assertThat(response.get("null_policy")).isEqualTo("overwrite");
	}

	@Test
	void setMergeRuleDefaultsNullsToKeepOriginal() {
		User athlete = newUser("write-tool-set-merge-rule-nulls-default-athlete@example.cc");
		authAs(athlete.getId(), "activities:read", "activities:write");

		var response = activityWriteTools.setMergeRule(
				"run", "device:coros", "device:garmin", List.of("heartrate"), null);

		assertThat(response.get("null_policy")).isEqualTo("keep_original");
	}

	@Test
	void setMergeRuleOverwritesAnExistingRuleForTheSameSport() {
		User athlete = newUser("write-tool-set-merge-rule-overwrite-athlete@example.cc");
		authAs(athlete.getId(), "activities:read", "activities:write");
		activityWriteTools.setMergeRule("run", "device:coros", "device:garmin", List.of("heartrate"), "overwrite");

		var response = activityWriteTools.setMergeRule(
				"run", "device:apple_watch", "device:garmin", List.of("heartrate", "cadence"), "keep_original");

		assertThat(response.get("target_pattern")).isEqualTo("device:apple_watch");
		assertThat(response.get("fields")).isEqualTo(List.of("heartrate", "cadence"));
	}

	@Test
	void setMergeRuleRejectsAMalformedPattern() {
		User athlete = newUser("write-tool-set-merge-rule-bad-pattern-athlete@example.cc");
		authAs(athlete.getId(), "activities:read", "activities:write");

		assertThatThrownBy(() -> activityWriteTools.setMergeRule(
				"run", "coros", "device:garmin", List.of("heartrate"), null))
				.isInstanceOf(ValidationException.class);
	}

	@Test
	void setMergeRuleRejectsAnUnknownField() {
		User athlete = newUser("write-tool-set-merge-rule-bad-field-athlete@example.cc");
		authAs(athlete.getId(), "activities:read", "activities:write");

		assertThatThrownBy(() -> activityWriteTools.setMergeRule(
				"run", "device:coros", "device:garmin", List.of("not_a_real_field"), null))
				.isInstanceOf(ValidationException.class);
	}

	@Test
	void setMergeRuleRejectsMultisportAndTransition() {
		User athlete = newUser("write-tool-set-merge-rule-multisport-athlete@example.cc");
		authAs(athlete.getId(), "activities:read", "activities:write");

		assertThatThrownBy(() -> activityWriteTools.setMergeRule(
				"multisport", "device:coros", "device:garmin", List.of("heartrate"), null))
				.isInstanceOf(ValidationException.class);
	}

	private Activity newOverlapActivity(User athlete, String device, Instant startDate) {
		Activity activity = new Activity();
		activity.setAthlete(athlete);
		activity.setSport(Sport.BIKE);
		activity.setName(device + " ride");
		activity.setDevice(device);
		activity.setStartDate(startDate);
		activity.setElapsedSeconds(3600);
		activity.setMovingTime(3600);
		return activityRepository.save(activity);
	}

	@Test
	void resolveDuplicatesBatchDryRunPreviewsWithoutMutatingAnything() {
		User athlete = newUser("write-tool-batch-dry-run@example.cc");
		Instant start = Instant.parse("2026-06-01T18:00:00Z");
		Activity zwift = newOverlapActivity(athlete, "Zwift", start);
		Activity garmin = newOverlapActivity(athlete, "Garmin", start.plusSeconds(5));
		authAs(athlete.getId(), "activities:read", "activities:write");

		var result = activityWriteTools.resolveDuplicatesBatch("mark", java.util.List.of(java.util.List.of(garmin.getId(), zwift.getId())),
				null, null, null, null, null, null, null, null, null, null, null, null, null, null);

		assertThat(result.get("dry_run")).isEqualTo(true);
		assertThat(result.get("batch_id")).isNull();
		assertThat(activityRepository.findById(garmin.getId()).orElseThrow().getPrimaryActivity()).isNull();
	}

	@Test
	void resolveDuplicatesBatchApplyMarksAndUndoBatchReversesIt() {
		User athlete = newUser("write-tool-batch-apply@example.cc");
		Instant start = Instant.parse("2026-06-01T18:00:00Z");
		Activity zwift = newOverlapActivity(athlete, "Zwift", start);
		Activity garmin = newOverlapActivity(athlete, "Garmin", start.plusSeconds(5));
		authAs(athlete.getId(), "activities:read", "activities:write");

		var result = activityWriteTools.resolveDuplicatesBatch("mark", java.util.List.of(java.util.List.of(garmin.getId(), zwift.getId())),
				null, null, null, null, null, null, null, null, null, null, null, null, null, false);

		assertThat(result.get("dry_run")).isEqualTo(false);
		assertThat(result.get("batch_id")).isNotNull();
		assertThat(activityRepository.findById(garmin.getId()).orElseThrow().getPrimaryActivity().getId())
				.isEqualTo(zwift.getId());

		var undoResult = activityWriteTools.undoBatch((String) result.get("batch_id"));

		assertThat(undoResult.get("reverted_count")).isEqualTo(1);
		assertThat(activityRepository.findById(garmin.getId()).orElseThrow().getPrimaryActivity()).isNull();
	}

	private Tag newTag(User athlete, String name) {
		Tag tag = new Tag();
		tag.setAthlete(athlete);
		tag.setName(name);
		return tagRepository.save(tag);
	}

	private void attach(Activity activity, Tag tag) {
		ActivityTag link = new ActivityTag();
		link.setActivity(activity);
		link.setTag(tag);
		activityTagRepository.save(link);
	}

	@Test
	void renameTagMergesCaseInsensitively() {
		User athlete = newUser("write-tool-rename-tag-athlete@example.cc");
		Activity a1 = newActivity(athlete);
		Activity a2 = newActivity(athlete);
		Tag oldTag = newTag(athlete, "race");
		Tag target = newTag(athlete, "Race");
		attach(a1, oldTag);
		attach(a2, target);
		authAs(athlete.getId(), "activities:read", "activities:write");

		var result = activityWriteTools.renameTag("race", "Race");

		assertThat(result.id()).isEqualTo(target.getId());
		assertThat(result.count()).isEqualTo(2);
		assertThat(tagRepository.findById(oldTag.getId())).isEmpty();
	}

	@Test
	void renameTagRejectsEmptyNewName() {
		User athlete = newUser("write-tool-rename-tag-empty-athlete@example.cc");
		newTag(athlete, "Race");
		authAs(athlete.getId(), "activities:read", "activities:write");

		assertThatThrownBy(() -> activityWriteTools.renameTag("Race", "  "))
				.isInstanceOf(ValidationException.class);
	}

	@Test
	void renameTagNotFound() {
		User athlete = newUser("write-tool-rename-tag-missing-athlete@example.cc");
		authAs(athlete.getId(), "activities:read", "activities:write");

		assertThatThrownBy(() -> activityWriteTools.renameTag("Nonexistent", "New"))
				.isInstanceOf(NotFoundException.class);
	}

	@Test
	void deleteTagRemovesItAndItsLinks() {
		User athlete = newUser("write-tool-delete-tag-athlete@example.cc");
		Activity activity = newActivity(athlete);
		Tag tag = newTag(athlete, "Race");
		attach(activity, tag);
		authAs(athlete.getId(), "activities:read", "activities:write");

		var result = activityWriteTools.deleteTag("Race");

		assertThat(result).isEqualTo(Map.of("deleted", true, "name", "Race"));
		assertThat(tagRepository.findById(tag.getId())).isEmpty();
	}
}
