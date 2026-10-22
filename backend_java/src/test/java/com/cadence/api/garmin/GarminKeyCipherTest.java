package com.cadence.api.garmin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cadence.api.common.config.CadenceProperties;
import com.cadence.api.common.error.ApiException;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class GarminKeyCipherTest {

	private static final String KEY_A = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes());
	private static final String KEY_B = Base64.getEncoder().encodeToString("fedcba9876543210fedcba9876543210".getBytes());

	private static GarminKeyCipher cipher(String key) {
		return new GarminKeyCipher(new CadenceProperties(null, null, null, null, null, null, null,
				new CadenceProperties.Garmin(key, "http://unused")));
	}

	@Test
	void roundTripsAndNeverStoresPlaintext() {
		GarminKeyCipher c = cipher(KEY_A);
		String stored = c.encrypt("my-secret-garmin-key", "usr_1");

		assertThat(stored).startsWith("v1.").doesNotContain("my-secret-garmin-key");
		assertThat(c.decrypt(stored, "usr_1")).isEqualTo("my-secret-garmin-key");
	}

	@Test
	void decryptsAFixtureSharedWithTheDjangoBackend() {
		// The same literal is pinned in backend/garmin/tests.py, so either backend can read a row
		// the other wrote.
		assertThat(cipher(KEY_A).decrypt("v1.PIvZpTfrpcQb7tiDRkEIMoym01OQ5qPWPDmXLxmzC-q2h5PNzd-eDclZBq8", "usr_parity")).isEqualTo("parity-check-key");
	}

	@Test
	void usesAFreshIvEachTime() {
		GarminKeyCipher c = cipher(KEY_A);
		assertThat(c.encrypt("same", "usr_1")).isNotEqualTo(c.encrypt("same", "usr_1"));
	}

	@Test
	void ciphertextIsBoundToItsOwner() {
		GarminKeyCipher c = cipher(KEY_A);
		String stored = c.encrypt("k-for-user-1", "usr_1");

		assertThatThrownBy(() -> c.decrypt(stored, "usr_2")).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void aDifferentKeyCannotDecrypt() {
		String stored = cipher(KEY_A).encrypt("secret-value", "usr_1");

		assertThatThrownBy(() -> cipher(KEY_B).decrypt(stored, "usr_1")).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void tamperedCiphertextIsRejected() {
		GarminKeyCipher c = cipher(KEY_A);
		String stored = c.encrypt("secret-value", "usr_1");
		// A character in the middle always carries data bits (the last one can be padding).
		int mid = stored.length() / 2;
		String tampered = stored.substring(0, mid) + (stored.charAt(mid) == 'A' ? 'B' : 'A') + stored.substring(mid + 1);

		assertThatThrownBy(() -> c.decrypt(tampered, "usr_1")).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void failsClosedWhenNoKeyIsConfigured() {
		assertThatThrownBy(() -> cipher("").encrypt("x", "usr_1")).isInstanceOf(ApiException.class);
	}

	@Test
	void rejectsAKeyOfTheWrongLength() {
		assertThatThrownBy(() -> cipher(Base64.getEncoder().encodeToString("too-short".getBytes())))
				.isInstanceOf(IllegalStateException.class);
	}
}
