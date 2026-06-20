package com.cadence.api.security.oauth;

import org.springframework.security.jackson.SecurityJacksonModules;
import org.springframework.security.oauth2.server.authorization.jackson.OAuth2AuthorizationServerJacksonModule;
import tools.jackson.databind.json.JsonMapper;

/**
 * The same Jackson setup Spring Authorization Server's own {@code JdbcOAuth2AuthorizationService}
 * uses internally, so polymorphic attribute values (the authenticated principal, the original
 * authorization request) deserialize correctly when we read them back out of our own JPA entity.
 * Spring Boot 4 / Spring Security 7 default to Jackson 3 ({@code tools.jackson.databind}), so this
 * uses the non-"2"-suffixed module classes rather than the deprecated Jackson 2 equivalents.
 */
final class OAuthJacksonSupport {

	private OAuthJacksonSupport() {
	}

	static JsonMapper jsonMapper() {
		ClassLoader classLoader = OAuthJacksonSupport.class.getClassLoader();
		return JsonMapper.builder()
				.addModules(SecurityJacksonModules.getModules(classLoader))
				.addModule(new OAuth2AuthorizationServerJacksonModule())
				.build();
	}
}
