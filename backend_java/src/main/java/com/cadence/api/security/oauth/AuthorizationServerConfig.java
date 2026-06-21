package com.cadence.api.security.oauth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.core.OAuth2Token;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Spring Authorization Server, scoped to exactly the paths the contract documents:
 * {@code /oauth/authorize} (the authorization-code redirect dance) and {@code /oauth/token}
 * (code + refresh_token exchange) - not the framework's default {@code /oauth2/*} paths.
 * Token issuance uses {@link CadenceTokenGenerator} so values look like {@code cad_at_...}/
 * {@code cad_rt_...} instead of the framework's default opaque format.
 */
@Configuration
public class AuthorizationServerConfig {

	@Bean
	@Order(Ordered.HIGHEST_PRECEDENCE)
	public SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http) throws Exception {
		OAuth2AuthorizationServerConfigurer authorizationServerConfigurer = new OAuth2AuthorizationServerConfigurer();
		RequestMatcher endpointsMatcher = authorizationServerConfigurer.getEndpointsMatcher();

		http
				.securityMatcher(endpointsMatcher)
				.authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
				.csrf(csrf -> csrf.ignoringRequestMatchers(endpointsMatcher))
				.with(authorizationServerConfigurer, configurer -> configurer.tokenGenerator(tokenGenerator()))
				.exceptionHandling(exceptions -> exceptions.defaultAuthenticationEntryPointFor(
						new LoginUrlAuthenticationEntryPoint("/login"),
						new MediaTypeRequestMatcher(MediaType.TEXT_HTML)));

		return http.build();
	}

	@Bean
	public AuthorizationServerSettings authorizationServerSettings() {
		return AuthorizationServerSettings.builder()
				.authorizationEndpoint("/oauth/authorize")
				.tokenEndpoint("/oauth/token")
				.build();
	}

	@Bean
	public OAuth2TokenGenerator<OAuth2Token> tokenGenerator() {
		return new CadenceTokenGenerator();
	}
}
