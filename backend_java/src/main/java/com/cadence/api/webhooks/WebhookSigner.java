package com.cadence.api.webhooks;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public class WebhookSigner {

	private static final String SECRET_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
	private static final String SECRET_PREFIX = "whsec_";
	private static final SecureRandom RANDOM = new SecureRandom();

	public String generateSecret() {
		StringBuilder sb = new StringBuilder(SECRET_PREFIX.length() + 32);
		sb.append(SECRET_PREFIX);
		for (int i = 0; i < 32; i++) {
			sb.append(SECRET_ALPHABET.charAt(RANDOM.nextInt(SECRET_ALPHABET.length())));
		}
		return sb.toString();
	}

	public String sign(String secret, byte[] rawBody) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			return HexFormat.of().formatHex(mac.doFinal(rawBody));
		}
		catch (NoSuchAlgorithmException | InvalidKeyException e) {
			throw new IllegalStateException(e);
		}
	}
}
