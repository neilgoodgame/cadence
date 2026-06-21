package com.cadence.api.security.pat;

import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;

/** The result of successfully authenticating a {@code cad_pat_...} bearer token. */
public class PersonalAccessTokenAuthentication extends AbstractAuthenticationToken {

	private final String userId;
	private final PersonalAccessToken token;

	public PersonalAccessTokenAuthentication(String userId, PersonalAccessToken token) {
		super(List.of());
		this.userId = userId;
		this.token = token;
		setAuthenticated(true);
	}

	@Override
	public Object getCredentials() {
		return token.getId();
	}

	@Override
	public Object getPrincipal() {
		return userId;
	}

	public PersonalAccessToken token() {
		return token;
	}
}
