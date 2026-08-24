package com.cadence.api.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cadence.api.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Regression guard for a real bug found live-testing the MCP connector flow with an actual
 * browser (not curl, which never sends an {@code Origin} header): {@code SecurityConfig}'s CORS
 * configuration only allowed the SPA frontend's own origins, but modern browsers attach
 * {@code Origin} to POST requests regardless of same-origin-ness - so the browser-rendered
 * {@code /login} form in front of {@code /oauth/authorize} (needed for a real third-party OAuth
 * client like Claude's MCP connector, unlike the first-party client which bypasses it) got
 * rejected with "Invalid CORS request" even though the request was genuinely same-origin. Fixed
 * by adding the API's own issuer to the allowed-origins list.
 */
@AutoConfigureMockMvc
class CorsConfigTest extends IntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Value("${cadence.oauth.issuer}")
	private String issuer;

	@Test
	void loginPageAcceptsARequestOriginatingFromTheApisOwnOrigin() throws Exception {
		mockMvc.perform(get("/login").header(HttpHeaders.ORIGIN, issuer))
				.andExpect(status().isOk())
				.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, issuer));
	}
}
