package com.cadence.api.security;

import com.cadence.api.common.error.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** The 403 counterpart to {@link ApiAuthenticationEntryPoint} - same reasoning. */
@Component
public class ApiAccessDeniedHandler implements AccessDeniedHandler {

	private final JsonMapper jsonMapper;

	public ApiAccessDeniedHandler(JsonMapper jsonMapper) {
		this.jsonMapper = jsonMapper;
	}

	@Override
	public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException)
			throws java.io.IOException {
		response.setStatus(HttpStatus.FORBIDDEN.value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		ErrorResponse body = ErrorResponse.of("authorization_error", "forbidden",
				"You do not have permission to perform this action.", null);
		jsonMapper.writeValue(response.getOutputStream(), body);
	}
}
