package com.cadence.api.security.oauth;

import java.security.SecureRandom;
import java.time.Instant;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.OAuth2Token;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;

/**
 * Replaces Spring Authorization Server's default base64url opaque tokens with the
 * contract's {@code cad_at_}/{@code cad_rt_} prefixed format, so tokens issued through
 * the real {@code /oauth/token} endpoint and through {@link TokenIssuer}'s direct-issuance
 * bypass are indistinguishable on the wire.
 */
public class CadenceTokenGenerator implements OAuth2TokenGenerator<OAuth2Token> {

	private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
	private static final int RANDOM_LENGTH = 40;
	private static final SecureRandom RANDOM = new SecureRandom();

	public static String randomToken(String prefix) {
		StringBuilder sb = new StringBuilder(prefix.length() + RANDOM_LENGTH);
		sb.append(prefix);
		for (int i = 0; i < RANDOM_LENGTH; i++) {
			sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
		}
		return sb.toString();
	}

	@Override
	public OAuth2Token generate(OAuth2TokenContext context) {
		if (OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())) {
			Instant issuedAt = Instant.now();
			Instant expiresAt = issuedAt.plus(context.getRegisteredClient().getTokenSettings().getAccessTokenTimeToLive());
			return new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, randomToken("cad_at_"),
					issuedAt, expiresAt, context.getAuthorizedScopes());
		}
		if (OAuth2TokenType.REFRESH_TOKEN.equals(context.getTokenType())) {
			if (!context.getRegisteredClient().getAuthorizationGrantTypes().contains(AuthorizationGrantType.REFRESH_TOKEN)) {
				return null;
			}
			Instant issuedAt = Instant.now();
			Instant expiresAt = issuedAt.plus(context.getRegisteredClient().getTokenSettings().getRefreshTokenTimeToLive());
			return new OAuth2RefreshToken(randomToken("cad_rt_"), issuedAt, expiresAt);
		}
		return null;
	}
}
