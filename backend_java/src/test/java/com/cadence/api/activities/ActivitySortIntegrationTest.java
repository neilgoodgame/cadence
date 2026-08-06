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

/** Mirrors the Python backend's test_activity_list.py::test_sort_by_date_ascending_and_descending -
 * "date" is resolved by ActivityFieldMap the same way ActivityController.mergeSortIntoQuery would
 * turn a `sort=date` / `sort=-date` request param into an `orderby date asc|desc` CQL clause. */
class ActivitySortIntegrationTest extends IntegrationTest {

	@Autowired
	private ActivityService activityService;

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

	private void newActivity(User athlete, String name, Instant startDate) {
		Activity activity = new Activity();
		activity.setAthlete(athlete);
		activity.setSport(Sport.RUN);
		activity.setName(name);
		activity.setStartDate(startDate);
		activityRepository.save(activity);
	}

	@Test
	void sortByDateAscendingAndDescending() {
		User athlete = newUser("sort-date-athlete@example.cc");
		newActivity(athlete, "Earlier", Instant.parse("2026-01-01T07:00:00Z"));
		newActivity(athlete, "Later", Instant.parse("2026-03-01T07:00:00Z"));

		CursorPage<ActivityResponse> desc =
				activityService.list(athlete.getId(), "orderby date desc", null, null, null, null, null, null, 50);
		assertThat(desc.data()).extracting(ActivityResponse::name).containsExactly("Later", "Earlier");

		CursorPage<ActivityResponse> asc =
				activityService.list(athlete.getId(), "orderby date asc", null, null, null, null, null, null, 50);
		assertThat(asc.data()).extracting(ActivityResponse::name).containsExactly("Earlier", "Later");
	}
}
