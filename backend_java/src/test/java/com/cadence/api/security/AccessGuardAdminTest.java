package com.cadence.api.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cadence.api.common.error.ForbiddenException;
import com.cadence.api.support.IntegrationTest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class AccessGuardAdminTest extends IntegrationTest {

	@Autowired
	private AccessGuard accessGuard;

	@Autowired
	private UserRepository userRepository;

	@AfterEach
	void clearAuthContext() {
		AuthContextHolder.clear();
	}

	private User newUser(String email, boolean admin) {
		User user = new User();
		user.setEmail(email);
		user.setName("Test User " + email);
		user.setPassword("irrelevant-for-this-test");
		user.setAdmin(admin);
		return userRepository.save(user);
	}

	private void authAs(String userId) {
		AuthContextHolder.set(AuthContext.self(userId, Set.of(), AuthContext.CredentialKind.OAUTH2));
	}

	@Test
	void throwsForNonAdmin() {
		User user = newUser("non-admin@example.cc", false);
		authAs(user.getId());
		assertThatThrownBy(() -> accessGuard.requireAdmin()).isInstanceOf(ForbiddenException.class);
	}

	@Test
	void throwsForASubWithNoMatchingUserRow() {
		authAs("usr_doesnotexist");
		assertThatThrownBy(() -> accessGuard.requireAdmin()).isInstanceOf(ForbiddenException.class);
	}

	@Test
	void returnsSubForARealAdmin() {
		User admin = newUser("admin@example.cc", true);
		authAs(admin.getId());
		assertThat(accessGuard.requireAdmin()).isEqualTo(admin.getId());
	}
}
