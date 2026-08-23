package com.cadence.api.security.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cadence.api.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Regression guard for the two-{@link RegisteredClient} split introduced for the MCP server
 * (see {@link FirstPartyClientConfig}, {@link McpClientConfig}, {@link OAuthClientRepositoryConfig}):
 * the first-party client's trusted, consent-free behavior must stay exactly as it was, and the
 * new MCP client must actually require PKCE + consent, not just claim to in comments. Also
 * covers the two {@code /.well-known/} discovery endpoints the MCP OAuth flow depends on.
 */
@AutoConfigureMockMvc
class OAuthClientConfigTest extends IntegrationTest {

	@Autowired
	private RegisteredClientRepository registeredClientRepository;

	@Autowired
	private MockMvc mockMvc;

	@Test
	void firstPartyClientStillSkipsPkceAndConsent() {
		RegisteredClient client = registeredClientRepository.findByClientId(FirstPartyClientConfig.CLIENT_ID);
		assertThat(client).isNotNull();
		assertThat(client.getClientSettings().isRequireProofKey()).isFalse();
		assertThat(client.getClientSettings().isRequireAuthorizationConsent()).isFalse();
	}

	@Test
	void mcpClientRequiresPkceAndConsent() {
		RegisteredClient client = registeredClientRepository.findByClientId(McpClientConfig.CLIENT_ID);
		assertThat(client).isNotNull();
		assertThat(client.getClientSettings().isRequireProofKey()).isTrue();
		assertThat(client.getClientSettings().isRequireAuthorizationConsent()).isTrue();
		assertThat(client.getRedirectUris()).containsExactly("https://claude.ai/api/mcp/auth_callback");
		assertThat(client.getScopes()).doesNotContain("coach");
		// Regression guard for a real bug found via a live end-to-end OAuth+PKCE+consent flow
		// test: offline_access must be registered on the client itself, not just advertised in
		// the protected-resource metadata's scopes_supported - otherwise Spring Authorization
		// Server rejects Claude's authorization request outright as invalid_scope, before the
		// user ever reaches login.
		assertThat(client.getScopes()).contains("offline_access");
	}

	@Test
	void authorizationServerMetadataIsPubliclyFetchable() throws Exception {
		mockMvc.perform(get("/.well-known/oauth-authorization-server"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.issuer").exists())
				.andExpect(jsonPath("$.code_challenge_methods_supported", hasItem("S256")));
	}

	@Test
	void protectedResourceMetadataIsPubliclyFetchableAndCorrectlyShaped() throws Exception {
		mockMvc.perform(get("/.well-known/oauth-protected-resource"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.resource").value(org.hamcrest.Matchers.endsWith("/mcp")))
				.andExpect(jsonPath("$.authorization_servers").isArray())
				.andExpect(jsonPath("$.scopes_supported", hasItem("offline_access")))
				// bearerMethods() replaces rather than appends to the builder's own default -
				// see SecurityConfig's comment on why bearerMethod() alone would duplicate this.
				.andExpect(jsonPath("$.bearer_methods_supported.length()").value(1));
	}
}
