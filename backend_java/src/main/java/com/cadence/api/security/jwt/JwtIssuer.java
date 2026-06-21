package com.cadence.api.security.jwt;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Mints the short-lived, delegation-aware RS256 bearer JWTs returned by
 * {@code POST /v1/auth/jwt}. Distinct from - and outside of - Spring Authorization
 * Server's own token issuance: these carry a custom {@code athlete_id} claim that
 * authorizes the bearer to act on a specific athlete's data, separate from {@code sub}
 * (the principal who actually holds the credential).
 */
@Component
public class JwtIssuer {

	private final JwtKeys keys;

	public JwtIssuer(JwtKeys keys) {
		this.keys = keys;
	}

	public record Minted(String token, Map<String, Object> claims) {
	}

	public Minted mint(String sub, String athleteId, List<String> scopes, int expiresInSeconds) {
		Instant issuedAt = Instant.now();
		Instant expiresAt = issuedAt.plusSeconds(expiresInSeconds);
		String jti = "jwt_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
		String scope = String.join(" ", scopes);

		JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
				.issuer(keys.issuer())
				.subject(sub)
				.claim("athlete_id", athleteId)
				.audience(keys.audience())
				.claim("scope", scope)
				.issueTime(Date.from(issuedAt))
				.expirationTime(Date.from(expiresAt))
				.jwtID(jti)
				.build();

		JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(keys.kid()).build();
		SignedJWT signedJwt = new SignedJWT(header, claimsSet);
		try {
			signedJwt.sign(new RSASSASigner(keys.privateKey()));
		}
		catch (JOSEException e) {
			throw new IllegalStateException("Could not sign JWT.", e);
		}

		Map<String, Object> claims = new LinkedHashMap<>();
		claims.put("iss", keys.issuer());
		claims.put("sub", sub);
		claims.put("athlete_id", athleteId);
		claims.put("aud", keys.audience());
		claims.put("scope", scope);
		claims.put("iat", issuedAt.getEpochSecond());
		claims.put("exp", expiresAt.getEpochSecond());
		claims.put("jti", jti);

		return new Minted(signedJwt.serialize(), claims);
	}
}
