package com.cadence.api.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cadence.api.sharing.ShareRole;
import com.cadence.api.sharing.ShareStatus;
import com.cadence.api.sharing.UserRelationship;
import com.cadence.api.sharing.UserRelationshipRepository;
import com.cadence.api.support.IntegrationTest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Regression guard for a real bug found live-testing the MCP connector flow through Claude
 * Desktop: {@link AuthContextFilter} (see its own Javadoc) threw {@code IllegalStateException} on
 * the session-based {@code UsernamePasswordAuthenticationToken} the new {@code /login} form
 * produces, turning a successful login into a 500 that surfaced to Claude as a generic
 * "Authorization with Cadence Fitness failed" error - never caught by the earlier curl-scripted
 * verification, which drove the OAuth endpoints directly and never exercised a real form-login
 * POST the way a browser (or Claude Desktop's embedded one) does.
 */
@AutoConfigureMockMvc
class AuthContextFilterTest extends IntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private UserRelationshipRepository userRelationshipRepository;

	@Autowired
	private AuthContextFilter authContextFilter;

	@Test
	void sessionBasedFormLoginDoesNotCrashAuthContextFilter() throws Exception {
		User athlete = new User();
		athlete.setEmail("auth-context-filter-regression@example.cc");
		athlete.setName("Regression Athlete");
		athlete.setPassword(passwordEncoder.encode("test-password-123"));
		userRepository.save(athlete);

		MockHttpSession session = new MockHttpSession();
		mockMvc.perform(post("/login")
						.session(session)
						.param("username", athlete.getEmail())
						.param("password", "test-password-123"))
				.andExpect(status().is3xxRedirection());

		// The login POST itself never reaches AuthContextFilter (Spring's
		// SavedRequestAwareAuthenticationSuccessHandler sends its redirect without continuing the
		// filter chain) - the real crash happened one hop later: any *subsequent* request in the
		// same now-authenticated browser session that lands on this default security chain
		// (everything except /oauth/authorize and /oauth/token, which run on a separate chain -
		// see AuthorizationServerConfig) carries the session's UsernamePasswordAuthenticationToken
		// straight into AuthContextFilter. A real browser triggers this automatically (e.g.
		// fetching /favicon.ico right after the login page loads); reproduced directly here
		// against an arbitrary non-permitAll path instead of relying on that browser behavior.
		mockMvc.perform(get("/favicon.ico").session(session)).andExpect(result -> {
			int status = result.getResponse().getStatus();
			if (status >= 500) {
				throw new AssertionError(
						"Expected no 5xx, got " + status + ": " + result.getResponse().getContentAsString());
			}
		});
	}

	private User newUser(String email) {
		User user = new User();
		user.setEmail(email);
		user.setName("Test user");
		user.setPassword(passwordEncoder.encode("irrelevant-for-this-test"));
		return userRepository.save(user);
	}

	@Test
	void virtualCoachDelegatesToItsOneActiveAthlete() {
		User athlete = newUser("virtual-delegation-athlete@example.cc");
		User coach = newUser("virtual-delegation-coach@example.cc");
		coach.setVirtual(true);
		userRepository.save(coach);
		UserRelationship relationship = new UserRelationship();
		relationship.setOwner(athlete);
		relationship.setGrantee(coach);
		relationship.setRole(ShareRole.COACH);
		relationship.setStatus(ShareStatus.ACTIVE);
		userRelationshipRepository.save(relationship);

		assertThat(authContextFilter.virtualCoachDelegatedAthleteId(coach.getId())).contains(athlete.getId());
	}

	@Test
	void aRealCoachWithOneAthleteIsNotDelegated() {
		// The whole point of scoping this to isVirtual: a real user's own OAuth2 session (their
		// normal web app login) must never silently start showing someone else's data just
		// because they happen to coach exactly one athlete.
		User athlete = newUser("real-coach-delegation-athlete@example.cc");
		User coach = newUser("real-coach-delegation-coach@example.cc");
		UserRelationship relationship = new UserRelationship();
		relationship.setOwner(athlete);
		relationship.setGrantee(coach);
		relationship.setRole(ShareRole.COACH);
		relationship.setStatus(ShareStatus.ACTIVE);
		userRelationshipRepository.save(relationship);

		assertThat(authContextFilter.virtualCoachDelegatedAthleteId(coach.getId())).isEmpty();
	}

	@Test
	void aVirtualCoachWithNoActiveRelationshipIsNotDelegated() {
		User coach = newUser("virtual-delegation-orphan-coach@example.cc");
		coach.setVirtual(true);
		userRepository.save(coach);

		assertThat(authContextFilter.virtualCoachDelegatedAthleteId(coach.getId())).isEmpty();
	}
}
