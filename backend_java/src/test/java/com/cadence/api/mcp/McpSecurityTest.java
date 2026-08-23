package com.cadence.api.mcp;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cadence.api.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Regression guard for {@code SecurityConfig}'s {@code securityEntryPoint} bean: an
 * unauthenticated {@code /mcp} request must get {@link McpAuthenticationEntryPoint}'s
 * {@code WWW-Authenticate} discovery header (Claude's OAuth flow depends on it), while every
 * other endpoint's 401 behavior must stay exactly as it was before the MCP work - no header,
 * same JSON envelope.
 */
@AutoConfigureMockMvc
class McpSecurityTest extends IntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void unauthenticatedMcpRequestGetsDiscoveryHeader() throws Exception {
		mockMvc.perform(post("/mcp")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"jsonrpc\":\"2.0\",\"method\":\"initialize\",\"id\":1,\"params\":{}}"))
				.andExpect(status().isUnauthorized())
				.andExpect(header().string("WWW-Authenticate",
						"Bearer resource_metadata=\"https://api.cadence.cc/.well-known/oauth-protected-resource\""));
	}

	@Test
	void unauthenticatedRestRequestGetsNoDiscoveryHeader() throws Exception {
		mockMvc.perform(get("/v1/me"))
				.andExpect(status().isUnauthorized())
				.andExpect(header().doesNotExist("WWW-Authenticate"));
	}
}
