package com.cadence.api.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.cadence.api.admin.dto.CatalogAuditLogEntryResponse;
import com.cadence.api.support.IntegrationTest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class AdminAuditLogServiceIntegrationTest extends IntegrationTest {

	@Autowired
	private AdminAuditLogService service;

	@Autowired
	private UserRepository userRepository;

	private User newUser(String email) {
		User user = new User();
		user.setEmail(email);
		user.setName("User " + email);
		user.setPassword("irrelevant-for-this-test");
		return userRepository.save(user);
	}

	@Test
	void entriesOrderedNewestFirst() {
		User admin = newUser("audit-order@example.cc");
		service.logAdded("First", admin);
		service.logAdded("Second", admin);

		var entries = service.list();
		int firstIdx = indexOfDescription(entries, "First");
		int secondIdx = indexOfDescription(entries, "Second");

		assertThat(secondIdx).isLessThan(firstIdx);
	}

	@Test
	void entrySurvivesDeletionOfTheByUser() {
		User actor = newUser("audit-actor@example.cc");
		service.logAdded("Something", actor);
		userRepository.delete(actor);

		var entries = service.list();
		CatalogAuditLogEntryResponse row =
				entries.stream().filter(e -> e.description().equals("Something")).findFirst().orElseThrow();

		assertThat(row.by()).isNull();
	}

	private int indexOfDescription(java.util.List<CatalogAuditLogEntryResponse> entries, String description) {
		for (int i = 0; i < entries.size(); i++) {
			if (entries.get(i).description().equals(description)) {
				return i;
			}
		}
		throw new AssertionError("No entry with description " + description);
	}
}
