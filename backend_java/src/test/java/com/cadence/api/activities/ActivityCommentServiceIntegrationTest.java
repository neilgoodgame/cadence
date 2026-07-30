package com.cadence.api.activities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cadence.api.activities.dto.ActivityCommentResponse;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.common.error.ForbiddenException;
import com.cadence.api.sharing.ShareRole;
import com.cadence.api.sharing.ShareStatus;
import com.cadence.api.sharing.UserRelationship;
import com.cadence.api.sharing.UserRelationshipRepository;
import com.cadence.api.support.IntegrationTest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Mirrors the Python backend's activities/tests/test_comments.py. */
class ActivityCommentServiceIntegrationTest extends IntegrationTest {

	@Autowired
	private ActivityCommentService activityCommentService;

	@Autowired
	private ActivityRepository activityRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private UserRelationshipRepository userRelationshipRepository;

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

	private void grantRelationship(User owner, User grantee, ShareRole role) {
		UserRelationship relationship = new UserRelationship();
		relationship.setOwner(owner);
		relationship.setGrantee(grantee);
		relationship.setRole(role);
		relationship.setStatus(ShareStatus.ACTIVE);
		userRelationshipRepository.save(relationship);
	}

	@Test
	void athleteCommentIsLabeledWithAthleteRole() {
		User athlete = newUser("athlete-own-comment@example.cc");
		Activity activity = newActivity(athlete);

		ActivityCommentResponse response = activityCommentService.create(activity, athlete, "Felt great today");

		assertThat(response.text()).isEqualTo("Felt great today");
		assertThat(response.authorId()).isEqualTo(athlete.getId());
		assertThat(response.authorRole()).isEqualTo("athlete");
	}

	@Test
	void coachCommentIsLabeledWithCoachRole() {
		User athlete = newUser("athlete-coach-comment@example.cc");
		User coach = newUser("coach-comment@example.cc");
		grantRelationship(athlete, coach, ShareRole.COACH);
		Activity activity = newActivity(athlete);

		ActivityCommentResponse response = activityCommentService.create(activity, coach, "Good pacing");

		assertThat(response.authorRole()).isEqualTo("coach");
		assertThat(response.authorId()).isEqualTo(coach.getId());
	}

	@Test
	void viewerCommentIsLabeledWithViewerRole() {
		User athlete = newUser("athlete-viewer-comment@example.cc");
		User viewer = newUser("viewer-comment@example.cc");
		grantRelationship(athlete, viewer, ShareRole.VIEWER);
		Activity activity = newActivity(athlete);

		ActivityCommentResponse response = activityCommentService.create(activity, viewer, "Nice work");

		assertThat(response.authorRole()).isEqualTo("viewer");
	}

	@Test
	void commentsAreListedOldestFirst() {
		User athlete = newUser("order-athlete@example.cc");
		Activity activity = newActivity(athlete);
		activityCommentService.create(activity, athlete, "First");
		activityCommentService.create(activity, athlete, "Second");

		List<ActivityCommentResponse> comments = activityCommentService.list(activity.getId());

		assertThat(comments).extracting(ActivityCommentResponse::text).containsExactly("First", "Second");
	}

	@Test
	void authorCanDeleteOwnComment() {
		User athlete = newUser("delete-own-athlete@example.cc");
		Activity activity = newActivity(athlete);
		ActivityCommentResponse comment = activityCommentService.create(activity, athlete, "Oops");

		activityCommentService.delete(activity.getId(), comment.id(), athlete.getId());

		assertThat(activityCommentService.list(activity.getId())).isEmpty();
	}

	@Test
	void cannotDeleteSomeoneElsesComment() {
		User athlete = newUser("delete-other-athlete@example.cc");
		User coach = newUser("delete-other-coach@example.cc");
		grantRelationship(athlete, coach, ShareRole.COACH);
		Activity activity = newActivity(athlete);
		ActivityCommentResponse comment = activityCommentService.create(activity, athlete, "Mine");

		assertThatThrownBy(() -> activityCommentService.delete(activity.getId(), comment.id(), coach.getId()))
				.isInstanceOf(ForbiddenException.class);
		assertThat(activityCommentService.list(activity.getId())).hasSize(1);
	}
}
