package com.cadence.api.security;

import java.util.Set;

/**
 * The resolved identity of the current request, regardless of which of the three
 * bearer-credential schemes (OAuth2 access token, RS256 JWT, personal access token)
 * authenticated it.
 *
 * <p>{@code sub} is the authenticated principal - the user who is actually holding the
 * credential - and is never reassigned. {@code athleteId} is whose data the request is
 * authorized against; it differs from {@code sub} only for a JWT minted with a delegated
 * {@code athlete_id} claim (a coach acting on an athlete's data). Every permission check
 * compares {@code sub} against {@code athleteId}, not against the resource owner directly.
 */
public record AuthContext(String sub, String athleteId, Set<String> scopes, CredentialKind credentialKind) {

	public enum CredentialKind {
		OAUTH2, JWT, PERSONAL_ACCESS_TOKEN
	}

	public static AuthContext self(String userId, Set<String> scopes, CredentialKind kind) {
		return new AuthContext(userId, userId, scopes, kind);
	}
}
