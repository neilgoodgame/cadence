package com.cadence.api.security.oauth;

import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

/**
 * Composes every {@link RegisteredClient} bean ({@link FirstPartyClientConfig},
 * {@link McpClientConfig}) into the one repository. Still in-memory rather than a database
 * table - two never-changing, hand-configured clients doesn't yet justify one, though that
 * changes the day this system needs to support more than a small, known set of OAuth clients
 * (e.g. real Dynamic Client Registration).
 */
@Configuration
public class OAuthClientRepositoryConfig {

	@Bean
	public RegisteredClientRepository registeredClientRepository(List<RegisteredClient> clients) {
		return new InMemoryRegisteredClientRepository(clients);
	}
}
