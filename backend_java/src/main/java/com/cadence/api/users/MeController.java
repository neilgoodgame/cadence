package com.cadence.api.users;

import com.cadence.api.security.AuthContextHolder;
import com.cadence.api.users.dto.UserResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MeController {

	private final UserService userService;
	private final UserMapper userMapper;

	public MeController(UserService userService, UserMapper userMapper) {
		this.userService = userService;
		this.userMapper = userMapper;
	}

	@GetMapping("/v1/me")
	public UserResponse me() {
		String sub = AuthContextHolder.get().sub();
		return userMapper.toResponse(userService.getById(sub));
	}
}
