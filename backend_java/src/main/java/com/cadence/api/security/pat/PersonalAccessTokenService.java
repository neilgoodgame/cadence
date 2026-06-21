package com.cadence.api.security.pat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/** Generation, hashing, and constant-time verification for {@code cad_pat_...} secrets. */
@Component
public class PersonalAccessTokenService {

	private static final String TOKEN_PREFIX = "cad_pat_";
	private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
	private static final int SECRET_LENGTH = 28;
	private static final int VISIBLE_PREFIX_LENGTH = TOKEN_PREFIX.length() + 4;
	private static final SecureRandom RANDOM = new SecureRandom();

	public String generateSecret() {
		StringBuilder sb = new StringBuilder(TOKEN_PREFIX.length() + SECRET_LENGTH);
		sb.append(TOKEN_PREFIX);
		for (int i = 0; i < SECRET_LENGTH; i++) {
			sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
		}
		return sb.toString();
	}

	public String visiblePrefix(String secret) {
		return secret.substring(0, Math.min(VISIBLE_PREFIX_LENGTH, secret.length()));
	}

	public boolean looksLikePersonalAccessToken(String token) {
		return token.startsWith(TOKEN_PREFIX);
	}

	public String hash(String secret) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hash = digest.digest(secret.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(hash);
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	public boolean verify(String presentedSecret, String hashedSecret) {
		byte[] presentedHash = hash(presentedSecret).getBytes(StandardCharsets.UTF_8);
		byte[] storedHash = hashedSecret.getBytes(StandardCharsets.UTF_8);
		return MessageDigest.isEqual(presentedHash, storedHash);
	}
}
