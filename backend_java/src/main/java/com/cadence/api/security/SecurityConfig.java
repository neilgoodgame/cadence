package com.cadence.api.security;

import com.cadence.api.common.config.CadenceProperties;
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
import org.springframework.security.web.SecurityFilterChain;
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
		configuration.setAllowedOrigins(properties.cors().allowedOrigins());
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

	@Bean
	@Order(2)
	public SecurityFilterChain defaultSecurityFilterChain(HttpSecurity http,
			BearerSchemeAuthenticationManagerResolver resolver,
			AuthContextFilter authContextFilter,
			CorsConfigurationSource corsConfigurationSource,
			AuthenticationManager formLoginAuthenticationManager,
			ApiAuthenticationEntryPoint apiAuthenticationEntryPoint,
			ApiAccessDeniedHandler apiAccessDeniedHandler) throws Exception {
		http
				.csrf(AbstractHttpConfigurer::disable)
				.cors(cors -> cors.configurationSource(corsConfigurationSource))
				.authenticationManager(formLoginAuthenticationManager)
				.authorizeHttpRequests(authorize -> authorize
						.requestMatchers(
								"/v1/auth/register", "/.well-known/jwks.json", "/healthz",
								"/login", "/login/**",
								"/schema/**", "/swagger-ui/**", "/swagger-ui.html")
						.permitAll()
						.anyRequest().authenticated())
				.exceptionHandling(exceptions -> exceptions
						.defaultAuthenticationEntryPointFor(apiAuthenticationEntryPoint, request -> true)
						.defaultAccessDeniedHandlerFor(apiAccessDeniedHandler, request -> true))
				.formLogin(Customizer.withDefaults())
				.oauth2ResourceServer(resourceServer -> resourceServer
						.authenticationManagerResolver(resolver)
						.authenticationEntryPoint(apiAuthenticationEntryPoint)
						.accessDeniedHandler(apiAccessDeniedHandler))
				.addFilterAfter(authContextFilter, BearerTokenAuthenticationFilter.class);
		return http.build();
	}
}
