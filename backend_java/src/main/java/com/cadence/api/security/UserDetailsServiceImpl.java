package com.cadence.api.security;

import com.cadence.api.users.UserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Backs the form-login page in front of the {@code /oauth/authorize} authorization-code
 * flow - the one place a password is actually checked. Returns Spring Security's own
 * {@link org.springframework.security.core.userdetails.User}, not a custom principal type,
 * so it round-trips through Spring Security's Jackson modules untouched when Spring
 * Authorization Server persists it as part of an in-flight authorization.
 */
@Service
public class UserDetailsServiceImpl implements UserDetailsService {

	private final UserRepository userRepository;

	public UserDetailsServiceImpl(UserRepository userRepository) {
		this.userRepository = userRepository;
	}

	@Override
	public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
		com.cadence.api.users.User user = userRepository.findByEmailIgnoreCase(email)
				.orElseThrow(() -> new UsernameNotFoundException("No account with that email."));
		return org.springframework.security.core.userdetails.User.withUsername(user.getEmail())
				.password(user.getPassword() == null ? "{noop}disabled" : user.getPassword())
				.authorities("ROLE_USER")
				.disabled(!user.isActive())
				.build();
	}
}
