package com.cadence.api.security;

import com.cadence.api.common.config.CadenceProperties;
import com.cadence.api.mcp.McpAuthenticationEntryPoint;
import java.util.ArrayList;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.DelegatingAuthenticationEntryPoint;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcherEntry;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * The resource-server side of the API: everything except the Authorization Server's own
 * {@code /oauth/authorize}/{@code /oauth/token} endpoints (see {@code AuthorizationServerConfig}).
 * Bearer-scheme dispatch is delegated to {@link BearerSchemeAuthenticationManagerResolver};
 * {@link AuthContextFilter} runs immediately after to populate {@link AuthContextHolder}.
 */
@Configuration
public class SecurityConfig {

	@Bean
	public PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

	@Bean
	public CorsConfigurationSource corsConfigurationSource(CadenceProperties properties) {
		CorsConfiguration configuration = new CorsConfiguration();
		// The API's own origin is added automatically, not just properties.cors().allowedOrigins()
		// (the SPA frontend's origins) - Spring's CorsFilter treats ANY request carrying an Origin
		// header as needing to match this allowlist, including same-origin POSTs: modern browsers
		// attach Origin to POST requests regardless of same-origin-ness (unlike GET), so the
		// browser-rendered /login form (in front of /oauth/authorize, for a real third-party OAuth
		// client like Claude's MCP connector - see McpClientConfig) trips this even though it's
		// genuinely same-origin. Found live testing the MCP connector flow with an actual browser -
		// the earlier curl-scripted verification never caught this, since curl never sends Origin.
		List<String> allowedOrigins = new ArrayList<>(properties.cors().allowedOrigins());
		allowedOrigins.add(properties.oauth().issuer());
		configuration.setAllowedOrigins(allowedOrigins);
		configuration.setAllowedMethods(List.of("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"));
		configuration.setAllowedHeaders(List.of("*"));
		configuration.setAllowCredentials(true);
		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/**", configuration);
		return source;
	}

	/**
	 * The form-login page in front of {@code /oauth/authorize} needs its own explicit
	 * {@link AuthenticationManager} backed by {@link DaoAuthenticationProvider}. Spring Boot
	 * would normally auto-configure exactly this from the {@link UserDetailsService} +
	 * {@link PasswordEncoder} beans, but {@code PersonalAccessTokenAuthenticationProvider}
	 * being a discoverable {@code AuthenticationProvider} bean (needed so
	 * {@link BearerSchemeAuthenticationManagerResolver} can have it injected) makes Spring
	 * Security back off from that auto-configuration entirely - so it's wired explicitly here
	 * instead of relying on the global default.
	 */
	@Bean
	public AuthenticationManager formLoginAuthenticationManager(UserDetailsService userDetailsService, PasswordEncoder passwordEncoder) {
		DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
		provider.setPasswordEncoder(passwordEncoder);
		return new ProviderManager(provider);
	}

	/**
	 * {@code BearerTokenAuthenticationFilter} (wired via {@code oauth2ResourceServer()} below)
	 * handles its own authentication failures directly, bypassing {@code exceptionHandling()}'s
	 * per-matcher dispatch entirely - so a single entry point bean, used in both places, is the
	 * only way to guarantee {@code /mcp} actually gets {@link McpAuthenticationEntryPoint}'s
	 * {@code WWW-Authenticate} discovery header regardless of which code path rejects the
	 * request. Every other path falls through to {@code apiAuthenticationEntryPoint} unchanged.
	 */
	@Bean
	public AuthenticationEntryPoint securityEntryPoint(
			ApiAuthenticationEntryPoint apiAuthenticationEntryPoint, McpAuthenticationEntryPoint mcpAuthenticationEntryPoint) {
		return new DelegatingAuthenticationEntryPoint(apiAuthenticationEntryPoint,
				new RequestMatcherEntry<>(PathPatternRequestMatcher.pathPattern("/mcp"), mcpAuthenticationEntryPoint));
	}

	@Bean
	@Order(2)
	public SecurityFilterChain defaultSecurityFilterChain(HttpSecurity http,
			BearerSchemeAuthenticationManagerResolver resolver,
			AuthContextFilter authContextFilter,
			CorsConfigurationSource corsConfigurationSource,
			AuthenticationManager formLoginAuthenticationManager,
			AuthenticationEntryPoint securityEntryPoint,
			ApiAccessDeniedHandler apiAccessDeniedHandler,
			CadenceProperties properties) throws Exception {
		http
				.csrf(AbstractHttpConfigurer::disable)
				.cors(cors -> cors.configurationSource(corsConfigurationSource))
				.authenticationManager(formLoginAuthenticationManager)
				.authorizeHttpRequests(authorize -> authorize
						.requestMatchers(
								"/v1/auth/register", "/v1/auth/login", "/v1/auth/verify-email",
								"/.well-known/jwks.json", "/.well-known/oauth-protected-resource", "/healthz",
								"/login", "/login/**",
								"/schema/**", "/swagger-ui/**", "/swagger-ui.html")
						.permitAll()
						.anyRequest().authenticated())
				.exceptionHandling(exceptions -> exceptions
						.defaultAuthenticationEntryPointFor(securityEntryPoint, request -> true)
						.defaultAccessDeniedHandlerFor(apiAccessDeniedHandler, request -> true))
				.formLogin(Customizer.withDefaults())
				.oauth2ResourceServer(resourceServer -> resourceServer
						.authenticationManagerResolver(resolver)
						.authenticationEntryPoint(securityEntryPoint)
						.accessDeniedHandler(apiAccessDeniedHandler)
						// RFC 9728 protected-resource metadata for the MCP client's discovery
						// flow - Spring Security's own built-in filter, not hand-rolled.
						// offline_access is listed alongside the real scopes purely so Claude
						// auto-requests a refresh token (see McpClientConfig's Javadoc).
						.protectedResourceMetadata(prm -> prm.protectedResourceMetadataCustomizer(builder -> builder
								.resource(properties.oauth().issuer() + "/mcp")
								.authorizationServer(properties.oauth().issuer())
								.scope("activities:read")
								.scope("activities:write")
								.scope("workouts:write")
								.scope("calendar:write")
								.scope("gear:write")
								.scope("offline_access")
								// bearerMethods() (not bearerMethod()) - the builder pre-populates
								// "header" by default, and bearerMethod() appends rather than
								// replaces, which would otherwise duplicate the entry.
								.bearerMethods(methods -> {
									methods.clear();
									methods.add("header");
								}))))
				.addFilterAfter(authContextFilter, BearerTokenAuthenticationFilter.class);
		return http.build();
	}
}
