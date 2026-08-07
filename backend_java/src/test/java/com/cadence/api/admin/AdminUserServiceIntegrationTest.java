package com.cadence.api.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.cadence.api.admin.dto.AdminUserResponse;
import com.cadence.api.admin.dto.AdminUserUpdateRequest;
import com.cadence.api.support.IntegrationTest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class AdminUserServiceIntegrationTest extends IntegrationTest {

	@Autowired
	private AdminUserService service;

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
	void listIsUnscopedAcrossAllUsers() {
		newUser("list-a@example.cc", "A Lister");
		newUser("list-b@example.cc", "B Lister");

		List<String> emails = service.list(null).stream().map(AdminUserResponse::email).collect(Collectors.toList());

		assertThat(emails).contains("list-a@example.cc", "list-b@example.cc");
	}

	@Test
	void searchFiltersNameOrEmail() {
		newUser("sam.o@example.com", "Sam Ortega");
		newUser("priya@example.com", "Priya Nair");

		List<AdminUserResponse> results = service.list("sam");

		assertThat(results).hasSize(1);
		assertThat(results.get(0).name()).isEqualTo("Sam Ortega");
	}

	@Test
	void updateTogglesOnlyCoach() {
		User user = newUser("target@example.cc", "Target");
		AdminUserResponse updated = service.update(user.getId(), new AdminUserUpdateRequest(true, null));
		assertThat(updated.isCoach()).isTrue();
		assertThat(updated.isAdmin()).isFalse();
	}

	@Test
	void updateTogglesOnlyAdmin() {
		User user = newUser("target2@example.cc", "Target2");
		AdminUserResponse updated = service.update(user.getId(), new AdminUserUpdateRequest(null, true));
		assertThat(updated.isAdmin()).isTrue();
		assertThat(updated.isCoach()).isFalse();
	}

	@Test
	void updateCanSetBothFieldsTogether() {
		User user = newUser("target3@example.cc", "Target3");
		AdminUserResponse updated = service.update(user.getId(), new AdminUserUpdateRequest(true, true));
		assertThat(updated.isCoach()).isTrue();
		assertThat(updated.isAdmin()).isTrue();
	}
}
