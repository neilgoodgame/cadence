package com.cadence.api.users;

import com.cadence.api.security.oauth.TokenIssuer;
import com.cadence.api.users.dto.AuthResponse;
import com.cadence.api.users.dto.RegisterRequest;
import com.cadence.api.users.dto.TokenResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RegistrationController {

	private final UserService userService;
	private final UserMapper userMapper;
	private final TokenIssuer tokenIssuer;
	private final RegistrationRateLimiter rateLimiter;

	public RegistrationController(
			UserService userService, UserMapper userMapper, TokenIssuer tokenIssuer, RegistrationRateLimiter rateLimiter) {
		this.userService = userService;
		this.userMapper = userMapper;
		this.tokenIssuer = tokenIssuer;
		this.rateLimiter = rateLimiter;
	}

	@PostMapping("/v1/auth/register")
	@ResponseStatus(HttpStatus.CREATED)
	public AuthResponse register(@Valid @RequestBody RegisterRequest request, HttpServletRequest httpRequest) {
		rateLimiter.checkAndRecord(clientIp(httpRequest));
		User user = userService.register(request);
		TokenIssuer.TokenPair tokenPair = tokenIssuer.issueTokenPair(user);
		TokenResponse tokens = new TokenResponse(
				tokenPair.accessToken(), tokenPair.refreshToken(), "Bearer", tokenPair.expiresIn(), tokenPair.scope());
		return new AuthResponse(userMapper.toResponse(user), tokens);
	}

	/**
	 * The leftmost X-Forwarded-For entry is the original client - CloudFront appends it
	 * before forwarding to this instance, and the instance's security group only accepts
	 * traffic from CloudFront's own IPs in the first place, so this header can be trusted
	 * here (see infra/EC2_BACKEND_SKETCH.md). Falls back to the raw connection's address
	 * for local dev, where nothing sits in front to set the header at all.
	 */
	private String clientIp(HttpServletRequest request) {
		String forwardedFor = request.getHeader("X-Forwarded-For");
		if (forwardedFor != null && !forwardedFor.isBlank()) {
			return forwardedFor.split(",")[0].trim();
		}
		return request.getRemoteAddr();
	}
}
