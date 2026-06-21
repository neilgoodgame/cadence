package com.cadence.api.users;

import static org.assertj.core.api.Assertions.assertThat;

import com.cadence.api.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The first test in the "integration" bucket - proves the whole chain works, not just that a
 * mock returns what it's told to: a real Testcontainers Postgres/TimescaleDB boots, every Flyway
 * migration applies against it (so this also catches migration errors no unit test ever could),
 * Hibernate validates its entity mappings against that real schema, and a save/find round-trips
 * through an actual connection.
 */
class UserRepositoryIntegrationTest extends IntegrationTest {

	@Autowired
	private UserRepository userRepository;

	@Test
	void savesAndFindsAUserByEmailCaseInsensitively() {
		User user = new User();
		user.setEmail("integration-test@example.cc");
		user.setName("Integration Tester");
		user.setPassword("irrelevant-for-this-test");
		userRepository.save(user);

		var found = userRepository.findByEmailIgnoreCase("INTEGRATION-TEST@example.cc");

		assertThat(found).isPresent();
		assertThat(found.get().getId()).startsWith("usr_");
		assertThat(found.get().getName()).isEqualTo("Integration Tester");
	}
}
