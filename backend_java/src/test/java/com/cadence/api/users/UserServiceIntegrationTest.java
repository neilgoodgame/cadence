package com.cadence.api.users;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cadence.api.common.error.UnauthorizedException;
import com.cadence.api.support.IntegrationTest;
import com.cadence.api.users.dto.LoginRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Mirrors the Python backend's accounts/tests.py:LoginViewTests exactly. */
class UserServiceIntegrationTest extends IntegrationTest {

	@Autowired
	private UserService userService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private PasswordEncoder passwordEncoder;

	private User newUser(String email, String rawPassword) {
		User user = new User();
		user.setEmail(email);
		user.setName("Test User");
		if (rawPassword != null) {
			user.setPassword(passwordEncoder.encode(rawPassword));
		}
		return userRepository.save(user);
	}

	@Test
	void authenticateWithCorrectCredentialsSucceeds() {
		newUser("correct-creds@example.cc", "s3cret-pass");

		User authenticated = userService.authenticate(new LoginRequest("correct-creds@example.cc", "s3cret-pass"));

		assertThat(authenticated.getEmail()).isEqualTo("correct-creds@example.cc");
	}

	@Test
	void authenticateWithWrongPasswordIsUnauthorized() {
		newUser("wrong-pass@example.cc", "s3cret-pass");

		assertThatThrownBy(() -> userService.authenticate(new LoginRequest("wrong-pass@example.cc", "wrong-pass")))
				.isInstanceOf(UnauthorizedException.class);
	}

	@Test
	void authenticateWithUnknownEmailIsUnauthorized() {
		assertThatThrownBy(() -> userService.authenticate(new LoginRequest("nobody@example.cc", "whatever-pass")))
				.isInstanceOf(UnauthorizedException.class);
	}

	@Test
	void authenticateForSocialOnlyAccountIsUnauthorized() {
		newUser("social@example.cc", null);

		assertThatThrownBy(() -> userService.authenticate(new LoginRequest("social@example.cc", "anything-at-all")))
				.isInstanceOf(UnauthorizedException.class);
	}
}
