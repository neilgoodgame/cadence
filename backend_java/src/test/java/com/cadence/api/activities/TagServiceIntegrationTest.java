package com.cadence.api.activities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.cadence.api.activities.dto.TagResponse;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.common.error.ConflictException;
import com.cadence.api.common.error.NotFoundException;
import com.cadence.api.common.error.ValidationException;
import com.cadence.api.support.IntegrationTest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Mirrors the Python backend's activities/tests/test_tags.py::test_list_tags_includes_usage_count_ordered_desc. */
class TagServiceIntegrationTest extends IntegrationTest {

	@Autowired
	private TagService tagService;

	@Autowired
	private TagRepository tagRepository;

	@Autowired
	private ActivityTagRepository activityTagRepository;

	@Autowired
	private ActivityRepository activityRepository;

	@Autowired
	private UserRepository userRepository;

	private User newUser(String email) {
		User user = new User();
		user.setEmail(email);
		user.setName("Test User " + email);
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

	private void attach(Activity activity, Tag tag) {
		ActivityTag link = new ActivityTag();
		link.setActivity(activity);
		link.setTag(tag);
		activityTagRepository.save(link);
	}

	private Tag newTag(User athlete, String name) {
		Tag tag = new Tag();
		tag.setAthlete(athlete);
		tag.setName(name);
		return tagRepository.save(tag);
	}

	@Test
	void listTagsIncludesUsageCountOrderedDesc() {
		User athlete = newUser("tag-count-athlete@example.cc");
		Tag popular = new Tag();
		popular.setAthlete(athlete);
		popular.setName("Popular");
		popular = tagRepository.save(popular);
		Tag rare = new Tag();
		rare.setAthlete(athlete);
		rare.setName("Rare");
		rare = tagRepository.save(rare);

		Activity a1 = newActivity(athlete);
		Activity a2 = newActivity(athlete);
		attach(a1, popular);
		attach(a2, popular);
		attach(a1, rare);

		List<TagResponse> tags = tagService.listTagsWithCounts(athlete.getId());

		assertThat(tags).extracting(TagResponse::name, TagResponse::count)
				.containsExactly(tuple("Popular", 2L), tuple("Rare", 1L));
	}

	@Test
	void createTagSucceeds() {
		User athlete = newUser("create-tag-athlete@example.cc");

		Tag tag = tagService.createTag(athlete.getId(), athlete, "Trail");

		assertThat(tag.getName()).isEqualTo("Trail");
		assertThat(tag.getOrigin()).isEqualTo(TagOrigin.MANUAL);
		assertThat(tagRepository.findById(tag.getId())).isPresent();
	}

	@Test
	void createTagRejectsEmptyName() {
		User athlete = newUser("create-tag-empty-athlete@example.cc");

		assertThatThrownBy(() -> tagService.createTag(athlete.getId(), athlete, "  "))
				.isInstanceOf(ValidationException.class);
	}

	@Test
	void createTagConflictsCaseInsensitivelyWithAnExistingTag() {
		User athlete = newUser("create-tag-conflict-athlete@example.cc");
		newTag(athlete, "Race");

		assertThatThrownBy(() -> tagService.createTag(athlete.getId(), athlete, "race"))
				.isInstanceOf(ConflictException.class);
		assertThat(tagRepository.findAllByAthleteIdAndNameIgnoreCase(athlete.getId(), "race")).hasSize(1);
	}

	@Test
	void createTagDoesNotConflictWithAnotherAthletesTag() {
		User athlete = newUser("create-tag-scoped-athlete@example.cc");
		User other = newUser("create-tag-scoped-other@example.cc");
		newTag(other, "Race");

		Tag tag = tagService.createTag(athlete.getId(), athlete, "Race");

		assertThat(tag.getAthlete().getId()).isEqualTo(athlete.getId());
	}

	@Test
	void deleteUnusedTagSucceeds() {
		User athlete = newUser("delete-unused-athlete@example.cc");
		Tag tag = newTag(athlete, "Unused");

		tagService.deleteTag(athlete.getId(), tag.getId());

		assertThat(tagRepository.findById(tag.getId())).isEmpty();
	}

	@Test
	void deleteTagInUseRemovesItAndItsLinks() {
		User athlete = newUser("delete-in-use-athlete@example.cc");
		Tag tag = newTag(athlete, "Race");
		Activity activity = newActivity(athlete);
		attach(activity, tag);

		tagService.deleteTag(athlete.getId(), tag.getId());

		assertThat(tagRepository.findById(tag.getId())).isEmpty();
		assertThat(activityTagRepository.existsByActivityIdAndTagId(activity.getId(), tag.getId())).isFalse();
	}

	@Test
	void cannotDeleteOtherAthletesTag() {
		User athlete = newUser("delete-not-mine-athlete@example.cc");
		User other = newUser("delete-not-mine-other@example.cc");
		Tag tag = newTag(other, "Not mine");

		assertThatThrownBy(() -> tagService.deleteTag(athlete.getId(), tag.getId()))
				.isInstanceOf(NotFoundException.class);
		assertThat(tagRepository.findById(tag.getId())).isPresent();
	}

	@Test
	void renameTagToAFreeName() {
		User athlete = newUser("rename-free-athlete@example.cc");
		Tag tag = newTag(athlete, "Race");

		Tag result = tagService.renameTag(athlete.getId(), tag.getId(), "Key Race");

		assertThat(result.getId()).isEqualTo(tag.getId());
		assertThat(result.getName()).isEqualTo("Key Race");
	}

	@Test
	void renameTagMergesIntoAnExistingTagCaseInsensitively() {
		User athlete = newUser("rename-merge-athlete@example.cc");
		Activity a1 = newActivity(athlete);
		Activity a2 = newActivity(athlete);
		Tag oldTag = newTag(athlete, "race");
		Tag target = newTag(athlete, "Race");
		attach(a1, oldTag);
		attach(a2, target);

		Tag result = tagService.renameTag(athlete.getId(), oldTag.getId(), "Race");

		assertThat(result.getId()).isEqualTo(target.getId());
		assertThat(tagRepository.findById(oldTag.getId())).isEmpty();
		assertThat(activityTagRepository.existsByActivityIdAndTagId(a1.getId(), target.getId())).isTrue();
		assertThat(activityTagRepository.existsByActivityIdAndTagId(a2.getId(), target.getId())).isTrue();
		assertThat(activityTagRepository.countByTagId(target.getId())).isEqualTo(2);
	}

	@Test
	void renameTagMergeDoesNotDuplicateALinkAnActivityAlreadyHasOnBothSides() {
		User athlete = newUser("rename-merge-dedupe-athlete@example.cc");
		Activity activity = newActivity(athlete);
		Tag oldTag = newTag(athlete, "race");
		Tag target = newTag(athlete, "Race");
		attach(activity, oldTag);
		attach(activity, target);

		tagService.renameTag(athlete.getId(), oldTag.getId(), "Race");

		assertThat(activityTagRepository.countByTagId(target.getId())).isEqualTo(1);
	}

	@Test
	void renameTagToItsOwnCurrentNameIsANoOp() {
		User athlete = newUser("rename-noop-athlete@example.cc");
		Tag tag = newTag(athlete, "Race");

		Tag result = tagService.renameTag(athlete.getId(), tag.getId(), "Race");

		assertThat(result.getId()).isEqualTo(tag.getId());
		assertThat(result.getName()).isEqualTo("Race");
	}

	@Test
	void renameTagRejectsEmptyName() {
		User athlete = newUser("rename-empty-athlete@example.cc");
		Tag tag = newTag(athlete, "Race");

		assertThatThrownBy(() -> tagService.renameTag(athlete.getId(), tag.getId(), "  "))
				.isInstanceOf(ValidationException.class);
	}

	@Test
	void cannotRenameOtherAthletesTag() {
		User athlete = newUser("rename-not-mine-athlete@example.cc");
		User other = newUser("rename-not-mine-other@example.cc");
		Tag tag = newTag(other, "Not mine");

		assertThatThrownBy(() -> tagService.renameTag(athlete.getId(), tag.getId(), "Mine now"))
				.isInstanceOf(NotFoundException.class);
	}
}
