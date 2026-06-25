package com.cadence.api.users;

import com.cadence.api.common.error.ConflictException;
import com.cadence.api.common.error.NotFoundException;
import com.cadence.api.common.error.UnauthorizedException;
import com.cadence.api.users.dto.LoginRequest;
import com.cadence.api.users.dto.RegisterRequest;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

	private final UserRepository userRepository;
	private final PasswordEncoder passwordEncoder;

	public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
		this.userRepository = userRepository;
		this.passwordEncoder = passwordEncoder;
	}

	@Transactional
	public User register(RegisterRequest request) {
		if (request.email() != null && userRepository.existsByEmailIgnoreCase(request.email())) {
			throw new ConflictException("An account with that email already exists.");
		}

		User user = new User();
		user.setName(request.name());
		if (request.provider() != null && !request.provider().isBlank()) {
			String email = (request.email() != null && !request.email().isBlank())
					? request.email()
					: request.provider() + "+" + UUID.randomUUID() + "@social.cadence.invalid";
			user.setEmail(email);
		}
		else {
			user.setEmail(request.email());
			user.setPassword(passwordEncoder.encode(request.password()));
		}
		return userRepository.save(user);
	}

	public User getById(String id) {
		return userRepository.findById(id).orElseThrow(() -> new NotFoundException("No such user."));
	}

	/**
	 * A social-only account (no password ever set) has a null {@code password}, and
	 * {@link PasswordEncoder#matches} throws on a null encoded value rather than just
	 * returning false - check for it explicitly instead of letting that surface as a 500.
	 */
	public User authenticate(LoginRequest request) {
		User user = userRepository.findByEmailIgnoreCase(request.email()).orElse(null);
		if (user == null || user.getPassword() == null || !passwordEncoder.matches(request.password(), user.getPassword())) {
			throw new UnauthorizedException("Invalid email or password.");
		}
		return user;
	}
}
