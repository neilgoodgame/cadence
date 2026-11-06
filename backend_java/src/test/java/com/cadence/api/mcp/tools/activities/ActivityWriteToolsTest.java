package com.cadence.api.mcp.tools.activities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cadence.api.activities.Activity;
import com.cadence.api.activities.ActivityRepository;
import com.cadence.api.activities.ActivityTag;
import com.cadence.api.activities.ActivityTagRepository;
import com.cadence.api.activities.Tag;
import com.cadence.api.activities.TagRepository;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.common.error.ForbiddenException;
import com.cadence.api.common.error.NotFoundException;
import com.cadence.api.common.error.ValidationException;
import com.cadence.api.gear.Bike;
import com.cadence.api.gear.BikeRepository;
import com.cadence.api.security.AuthContext;
import com.cadence.api.security.AuthContextHolder;
import com.cadence.api.support.IntegrationTest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import java.time.Instant;
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
