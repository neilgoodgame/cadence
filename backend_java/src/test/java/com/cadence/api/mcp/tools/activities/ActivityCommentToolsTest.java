package com.cadence.api.mcp.tools.activities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cadence.api.activities.Activity;
import com.cadence.api.activities.ActivityRepository;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.common.error.ForbiddenException;
import com.cadence.api.common.error.ValidationException;
import com.cadence.api.security.AuthContext;
import com.cadence.api.security.AuthContextHolder;
import com.cadence.api.support.IntegrationTest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ActivityCommentToolsTest extends IntegrationTest {

	@Autowired
	private ActivityCommentTools activityCommentTools;

	@Autowired
	private ActivityRepository activityRepository;

	@Autowired
	private UserRepository userRepository;

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

	private void authAs(String userId, String... scopes) {
		AuthContextHolder.set(AuthContext.self(userId, Set.of(scopes), AuthContext.CredentialKind.PERSONAL_ACCESS_TOKEN));
	}

	@Test
	void postsAndAttributesTheCommentToTheRealCaller() {
		User athlete = newUser("comment-tool-athlete@example.cc");
		Activity activity = newActivity(athlete);
		authAs(athlete.getId(), "activities:read", "activities:write");

		var response = activityCommentTools.postActivityComment(activity.getId(), "Nice pace today!", null);

		assertThat(response.text()).isEqualTo("Nice pace today!");
		assertThat(response.authorId()).isEqualTo(athlete.getId());
		assertThat(response.authorRole()).isEqualTo("athlete");
	}

	@Test
	void requiresTheActivitiesWriteScope() {
		User athlete = newUser("comment-tool-scope-athlete@example.cc");
		Activity activity = newActivity(athlete);
		authAs(athlete.getId(), "activities:read");

		assertThatThrownBy(() -> activityCommentTools.postActivityComment(activity.getId(), "Should fail", null))
				.isInstanceOf(ForbiddenException.class);
	}

	@Test
	void rejectsBlankOrOverlongText() {
		User athlete = newUser("comment-tool-validation-athlete@example.cc");
		Activity activity = newActivity(athlete);
		authAs(athlete.getId(), "activities:read", "activities:write");

		assertThatThrownBy(() -> activityCommentTools.postActivityComment(activity.getId(), "  ", null))
				.isInstanceOf(ValidationException.class);
		assertThatThrownBy(() -> activityCommentTools.postActivityComment(activity.getId(), "x".repeat(4001), null))
				.isInstanceOf(ValidationException.class);
	}

	@Test
	void anOutsiderWithNoShareCannotComment() {
		User athlete = newUser("comment-tool-outsider-athlete@example.cc");
		Activity activity = newActivity(athlete);
		User outsider = newUser("comment-tool-outsider@example.cc");
		authAs(outsider.getId(), "activities:read", "activities:write");

		assertThatThrownBy(() -> activityCommentTools.postActivityComment(activity.getId(), "Should fail", null))
				.isInstanceOf(ForbiddenException.class);
	}

	@Test
	void listsCommentsOldestFirst() {
		User athlete = newUser("comment-tool-list-athlete@example.cc");
		Activity activity = newActivity(athlete);
		authAs(athlete.getId(), "activities:read", "activities:write");
		activityCommentTools.postActivityComment(activity.getId(), "First", null);
		activityCommentTools.postActivityComment(activity.getId(), "Second", null);

		authAs(athlete.getId(), "activities:read");
		var comments = activityCommentTools.listActivityComments(activity.getId());

		assertThat(comments).hasSize(2);
		assertThat(comments.get(0).text()).isEqualTo("First");
		assertThat(comments.get(1).text()).isEqualTo("Second");
	}

	@Test
	void listCommentsRequiresTheActivitiesReadScope() {
		User athlete = newUser("comment-tool-list-scope-athlete@example.cc");
		Activity activity = newActivity(athlete);
		authAs(athlete.getId(), "workouts:write");

		assertThatThrownBy(() -> activityCommentTools.listActivityComments(activity.getId()))
				.isInstanceOf(ForbiddenException.class);
	}

	@Test
	void listCommentsRejectsAnOutsiderWithNoShare() {
		User athlete = newUser("comment-tool-list-outsider-athlete@example.cc");
		Activity activity = newActivity(athlete);
		User outsider = newUser("comment-tool-list-outsider@example.cc");
		authAs(outsider.getId(), "activities:read");

		assertThatThrownBy(() -> activityCommentTools.listActivityComments(activity.getId()))
				.isInstanceOf(ForbiddenException.class);
	}

	@Test
	void aReplyIsAttachedToItsParent() {
		User athlete = newUser("comment-tool-reply-athlete@example.cc");
		Activity activity = newActivity(athlete);
		authAs(athlete.getId(), "activities:read", "activities:write");
		var root = activityCommentTools.postActivityComment(activity.getId(), "Root", null);

		var reply = activityCommentTools.postActivityComment(activity.getId(), "Reply", root.id());

		assertThat(reply.parentId()).isEqualTo(root.id());
	}

	@Test
	void cannotReplyToAReply() {
		User athlete = newUser("comment-tool-no-nested-reply-athlete@example.cc");
		Activity activity = newActivity(athlete);
		authAs(athlete.getId(), "activities:read", "activities:write");
		var root = activityCommentTools.postActivityComment(activity.getId(), "Root", null);
		var reply = activityCommentTools.postActivityComment(activity.getId(), "Reply", root.id());

		assertThatThrownBy(() -> activityCommentTools.postActivityComment(activity.getId(), "Reply to a reply", reply.id()))
				.isInstanceOf(ValidationException.class);
	}
}
