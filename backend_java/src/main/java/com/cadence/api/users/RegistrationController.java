package com.cadence.api.users;

import com.cadence.api.security.oauth.RegistrationTokenIssuer;
import com.cadence.api.users.dto.RegisterRequest;
import com.cadence.api.users.dto.RegisterResponse;
import com.cadence.api.users.dto.TokenResponse;
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
	private final RegistrationTokenIssuer tokenIssuer;

	public RegistrationController(UserService userService, UserMapper userMapper, RegistrationTokenIssuer tokenIssuer) {
		this.userService = userService;
		this.userMapper = userMapper;
		this.tokenIssuer = tokenIssuer;
	}

	@PostMapping("/v1/auth/register")
	@ResponseStatus(HttpStatus.CREATED)
	public RegisterResponse register(@Valid @RequestBody RegisterRequest request) {
		User user = userService.register(request);
		RegistrationTokenIssuer.TokenPair tokenPair = tokenIssuer.issueInitialTokenPair(user);
		TokenResponse tokens = new TokenResponse(
				tokenPair.accessToken(), tokenPair.refreshToken(), "Bearer", tokenPair.expiresIn(), tokenPair.scope());
		return new RegisterResponse(userMapper.toResponse(user), tokens);
	}
}
