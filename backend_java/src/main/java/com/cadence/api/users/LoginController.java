package com.cadence.api.users;

import com.cadence.api.security.oauth.TokenIssuer;
import com.cadence.api.users.dto.AuthResponse;
import com.cadence.api.users.dto.LoginRequest;
import com.cadence.api.users.dto.TokenResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class LoginController {

	private final UserService userService;
	private final UserMapper userMapper;
	private final TokenIssuer tokenIssuer;

	public LoginController(UserService userService, UserMapper userMapper, TokenIssuer tokenIssuer) {
		this.userService = userService;
		this.userMapper = userMapper;
		this.tokenIssuer = tokenIssuer;
	}

	@PostMapping("/v1/auth/login")
	public AuthResponse login(@Valid @RequestBody LoginRequest request) {
		User user = userService.authenticate(request);
		TokenIssuer.TokenPair tokenPair = tokenIssuer.issueTokenPair(user);
		TokenResponse tokens = new TokenResponse(
				tokenPair.accessToken(), tokenPair.refreshToken(), "Bearer", tokenPair.expiresIn(), tokenPair.scope());
		return new AuthResponse(userMapper.toResponse(user), tokens);
	}
}
