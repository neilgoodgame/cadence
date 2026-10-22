package com.cadence.api.garmin;

import com.cadence.api.common.config.CadenceProperties;
import com.cadence.api.common.error.ApiException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * AES-256-GCM for Garmin Personal API keys at rest. The key comes from
 * {@code cadence.garmin.encryption-key} (base64, 32 bytes); it is deliberately not defaulted, so
 * an environment that forgot to set it fails closed (503 on save) rather than quietly using a
 * well-known key. The owning athlete's id is bound in as AAD so a ciphertext copied onto another
 * athlete's row fails to decrypt.
 */
@Component
public class GarminKeyCipher {

	private static final String VERSION = "v1.";
	private static final int IV_BYTES = 12;
	private static final int TAG_BITS = 128;

	private final SecureRandom random = new SecureRandom();
	private final SecretKey key;

	public GarminKeyCipher(CadenceProperties properties) {
		String configured = properties.garmin() == null ? null : properties.garmin().encryptionKey();
		if (configured == null || configured.isBlank()) {
			this.key = null;
			return;
		}
		byte[] raw;
		try {
			raw = Base64.getDecoder().decode(configured.trim());
		}
		catch (IllegalArgumentException e) {
			throw new IllegalStateException("cadence.garmin.encryption-key must be base64.");
		}
		if (raw.length != 32) {
			throw new IllegalStateException("cadence.garmin.encryption-key must decode to exactly 32 bytes.");
		}
		this.key = new SecretKeySpec(raw, "AES");
	}

	public String encrypt(String plaintext, String aad) {
		SecretKey k = requireKey();
		try {
			byte[] iv = new byte[IV_BYTES];
			random.nextBytes(iv);
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.ENCRYPT_MODE, k, new GCMParameterSpec(TAG_BITS, iv));
			cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
			byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
			byte[] out = ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array();
			return VERSION + Base64.getUrlEncoder().withoutPadding().encodeToString(out);
		}
		catch (GeneralSecurityException e) {
			throw new IllegalStateException("Could not encrypt the Garmin key.", e);
		}
	}

	public String decrypt(String stored, String aad) {
		SecretKey k = requireKey();
		if (!stored.startsWith(VERSION)) {
			throw new IllegalStateException("Unrecognised Garmin key ciphertext version.");
		}
		try {
			byte[] in = Base64.getUrlDecoder().decode(stored.substring(VERSION.length()));
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.DECRYPT_MODE, k, new GCMParameterSpec(TAG_BITS, in, 0, IV_BYTES));
			cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
			return new String(cipher.doFinal(in, IV_BYTES, in.length - IV_BYTES), StandardCharsets.UTF_8);
		}
		catch (GeneralSecurityException | IllegalArgumentException e) {
			throw new IllegalStateException("Could not decrypt the Garmin key.", e);
		}
	}

	private SecretKey requireKey() {
		if (key == null) {
			throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Garmin sync is not configured on this server.");
		}
		return key;
	}
}
