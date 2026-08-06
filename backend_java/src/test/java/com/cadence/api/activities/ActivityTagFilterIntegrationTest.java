package com.cadence.api.activities;

import static org.assertj.core.api.Assertions.assertThat;

import com.cadence.api.activities.dto.ActivityResponse;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.common.paging.CursorPage;
import com.cadence.api.support.IntegrationTest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Mirrors the Python backend's
 * test_activity_list.py::test_filter_by_tag_query_param_matches_multi_word_tag_name -
 * the CQL "tag &lt;name&gt;" clause only ever captures a single token as the value
 * (CqlGrammarParser), so a multi-word tag name silently matches nothing if smuggled through
 * `q`. The dedicated `tag` request param bypasses CQL entirely and must match on the full name. */
class ActivityTagFilterIntegrationTest extends IntegrationTest {

	@Autowired
	private ActivityService activityService;

	@Autowired
	private ActivityRepository activityRepository;

	@Autowired
	private TagRepository tagRepository;

	@Autowired
	private ActivityTagRepository activityTagRepository;

	@Autowired
	private UserRepository userRepository;

	private User newUser(String email) {
		User user = new User();
		user.setEmail(email);
		user.setName("Test User " + email);
		user.setPassword("irrelevant-for-this-test");
		return userRepository.save(user);
	}

	private Activity newActivity(User athlete, String name) {
		Activity activity = new Activity();
		activity.setAthlete(athlete);
		activity.setSport(Sport.RUN);
		activity.setName(name);
		activity.setStartDate(Instant.parse("2026-01-01T07:00:00Z"));
		return activityRepository.save(activity);
	}

	@Test
	void filterByTagParamMatchesMultiWordTagName() {
		User athlete = newUser("tag-filter-athlete@example.cc");
		Activity matching = newActivity(athlete, "Sauna Ride");
		newActivity(athlete, "Cold Run");
		Tag tag = new Tag();
		tag.setAthlete(athlete);
		tag.setName("Heat Training");
		tag = tagRepository.save(tag);
		ActivityTag link = new ActivityTag();
		link.setActivity(matching);
		link.setTag(tag);
		activityTagRepository.save(link);

		CursorPage<ActivityResponse> page = activityService.list(
				athlete.getId(), null, null, null, "Heat Training", null, null, null, 50);

		assertThat(page.data()).extracting(ActivityResponse::name).containsExactly("Sauna Ride");
	}
}
