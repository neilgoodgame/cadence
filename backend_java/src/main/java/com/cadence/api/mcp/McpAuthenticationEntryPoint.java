package com.cadence.api.mcp;

import com.cadence.api.common.config.CadenceProperties;
import com.cadence.api.common.error.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The {@code /mcp}-specific half of Claude's OAuth discovery handshake: per Anthropic's docs
 * (claude.com/docs/connectors/building/authentication), a client probing an MCP server first
 * sends an unauthenticated (or stale-token) request, and expects a {@code 401} whose
 * {@code WWW-Authenticate} header points at this server's RFC 9728 protected-resource metadata
 * (published by Spring Security's built-in filter - see {@code SecurityConfig}'s
 * {@code .protectedResourceMetadata(...)} customizer). Claude only falls back to probing
 * {@code .well-known} paths directly if this header is missing, so this - not just the metadata
 * document existing somewhere - is the primary discovery mechanism.
 *
 * <p>Registered in {@code SecurityConfig} as the entry point for the {@code /mcp} request
 * matcher specifically, ahead of {@link com.cadence.api.security.ApiAuthenticationEntryPoint}'s
 * catch-all - every other endpoint's 401 behavior is unchanged.
 */
@Component
public class McpAuthenticationEntryPoint implements AuthenticationEntryPoint {

	private final JsonMapper jsonMapper;
	private final CadenceProperties properties;

	public McpAuthenticationEntryPoint(JsonMapper jsonMapper, CadenceProperties properties) {
		this.jsonMapper = jsonMapper;
		this.properties = properties;
	}

	@Override
	public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
			throws java.io.IOException {
		String resourceMetadataUrl = properties.oauth().issuer() + "/.well-known/oauth-protected-resource";
		response.setStatus(HttpStatus.UNAUTHORIZED.value());
		response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer resource_metadata=\"" + resourceMetadataUrl + "\"");
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		ErrorResponse body = ErrorResponse.of("authentication_error", "unauthorized",
				"Missing or invalid credentials.", null);
		jsonMapper.writeValue(response.getOutputStream(), body);
	}
}
