package com.cadence.api.security;

import com.cadence.api.common.error.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Spring Security writes 401 responses directly from the filter chain, before a request
 * ever reaches a controller - so {@code ApiExceptionHandler} never sees them. This puts
 * unauthenticated requests through the same {@code {"error": {...}}} envelope as every
 * other error response.
 */
@Component
public class ApiAuthenticationEntryPoint implements AuthenticationEntryPoint {

	private final JsonMapper jsonMapper;

	public ApiAuthenticationEntryPoint(JsonMapper jsonMapper) {
		this.jsonMapper = jsonMapper;
	}

	@Override
	public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
			throws java.io.IOException {
		response.setStatus(HttpStatus.UNAUTHORIZED.value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		ErrorResponse body = ErrorResponse.of("authentication_error", "unauthorized",
				"Missing or invalid credentials.", null);
		jsonMapper.writeValue(response.getOutputStream(), body);
	}
}
