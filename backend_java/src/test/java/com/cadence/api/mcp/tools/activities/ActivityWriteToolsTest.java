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

class ActivityWriteToolsTest extends IntegrationTest {

	@Autowired
	private ActivityWriteTools activityWriteTools;

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
}
