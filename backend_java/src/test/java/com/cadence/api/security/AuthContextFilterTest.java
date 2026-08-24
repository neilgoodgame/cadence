package com.cadence.api.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
}
