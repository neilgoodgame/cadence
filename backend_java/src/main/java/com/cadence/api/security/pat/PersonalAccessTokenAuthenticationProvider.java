package com.cadence.api.security.pat;

import java.time.LocalDate;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.stereotype.Component;

@Component
public class PersonalAccessTokenAuthenticationProvider implements AuthenticationProvider {

	private final PersonalAccessTokenRepository repository;
	private final PersonalAccessTokenService service;

	public PersonalAccessTokenAuthenticationProvider(PersonalAccessTokenRepository repository, PersonalAccessTokenService service) {
		this.repository = repository;
		this.service = service;
	}

	@Override
	public Authentication authenticate(Authentication authentication) {
		String presented = ((BearerTokenAuthenticationToken) authentication).getToken();
		String prefix = service.visiblePrefix(presented);

		PersonalAccessToken token = repository.findByPrefix(prefix)
				.filter(candidate -> service.verify(presented, candidate.getHashedSecret()))
				.orElseThrow(() -> new BadCredentialsException("Invalid personal access token."));

		if (token.getExpiresAt() != null && token.getExpiresAt().isBefore(LocalDate.now())) {
			throw new BadCredentialsException("This personal access token has expired.");
		}

		LocalDate today = LocalDate.now();
		if (token.getLastUsed() == null || !token.getLastUsed().isEqual(today)) {
			token.setLastUsed(today);
			repository.save(token);
		}

		return new PersonalAccessTokenAuthentication(token.getUser().getId(), token);
	}

	@Override
	public boolean supports(Class<?> authentication) {
		return BearerTokenAuthenticationToken.class.isAssignableFrom(authentication);
	}
}
