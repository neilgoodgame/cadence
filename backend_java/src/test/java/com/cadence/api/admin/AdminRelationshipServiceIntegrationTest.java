package com.cadence.api.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cadence.api.admin.dto.AdminRelationshipResponse;
import com.cadence.api.common.error.NotFoundException;
import com.cadence.api.sharing.ShareRole;
import com.cadence.api.sharing.ShareStatus;
import com.cadence.api.sharing.UserRelationship;
import com.cadence.api.sharing.UserRelationshipRepository;
import com.cadence.api.support.IntegrationTest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class AdminRelationshipServiceIntegrationTest extends IntegrationTest {

	@Autowired
	private AdminRelationshipService service;

	@Autowired
	private UserRelationshipRepository userRelationshipRepository;

	@Autowired
	private UserRepository userRepository;

	private User newUser(String email, String name) {
		User user = new User();
		user.setEmail(email);
		user.setName(name);
		user.setPassword("irrelevant-for-this-test");
		return userRepository.save(user);
	}

	@Test
	void listSpansAllOwnersNotJustOneAdmin() {
		User coach = newUser("rel-coach@example.cc", "Coach");
		User athlete = newUser("rel-athlete@example.cc", "Athlete");
		UserRelationship relationship = new UserRelationship();
		relationship.setOwner(athlete);
		relationship.setGrantee(coach);
		relationship.setRole(ShareRole.COACH);
		relationship.setStatus(ShareStatus.ACTIVE);
		userRelationshipRepository.save(relationship);

		java.util.List<AdminRelationshipResponse> results = service.list();
		AdminRelationshipResponse found =
				results.stream().filter(r -> r.id().equals(relationship.getId())).findFirst().orElseThrow();

		assertThat(found.coachName()).isEqualTo("Coach");
		assertThat(found.athleteName()).isEqualTo("Athlete");
		assertThat(found.role()).isEqualTo(ShareRole.COACH);
	}

	@Test
	void revokeSucceedsOnARelationshipTheAdminDoesNotOwn() {
		User coach = newUser("rel-coach2@example.cc", "Coach2");
		User athlete = newUser("rel-athlete2@example.cc", "Athlete2");
		UserRelationship relationship = new UserRelationship();
		relationship.setOwner(athlete);
		relationship.setGrantee(coach);
		relationship.setRole(ShareRole.COACH);
		relationship.setStatus(ShareStatus.ACTIVE);
		userRelationshipRepository.save(relationship);
		String id = relationship.getId();

		service.revoke(id);

		assertThat(userRelationshipRepository.findById(id)).isEmpty();
	}

	@Test
	void revokeUnknownIdThrowsNotFound() {
		assertThatThrownBy(() -> service.revoke("rel_doesnotexist")).isInstanceOf(NotFoundException.class);
	}
}
