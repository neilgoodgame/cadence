package com.cadence.api.imports;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cadence.api.common.error.ForbiddenException;
import com.cadence.api.security.AuthContext;
import com.cadence.api.security.AuthContextHolder;
import com.cadence.api.support.IntegrationTest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;

class ImportControllerIntegrationTest extends IntegrationTest {

	@Autowired
	private ImportController importController;

	@Autowired
	private UserRepository userRepository;

	@AfterEach
	void clearAuthContext() {
		AuthContextHolder.clear();
	}

	private User newAthlete(String email, boolean emailVerified) {
		User user = new User();
		user.setEmail(email);
		user.setName("Import Athlete");
		user.setPassword("irrelevant-for-this-test");
		user.setEmailVerified(emailVerified);
		return userRepository.save(user);
	}

	private void authAs(String userId) {
		AuthContextHolder.set(AuthContext.self(userId, Set.of("activities:read", "activities:write"), AuthContext.CredentialKind.OAUTH2));
	}

	@Test
	void startImportRejectsAnUnverifiedAthlete() {
		User athlete = newAthlete("import-unverified@example.cc", false);
		authAs(athlete.getId());
		MockMultipartFile file = new MockMultipartFile("file", "export.json.gz", "application/gzip", new byte[0]);

		assertThatThrownBy(() -> importController.startImport(file)).isInstanceOf(ForbiddenException.class);
	}
}
