package com.cadence.api.common.id;

import java.security.SecureRandom;

/**
 * Stripe-style resource identifiers: {@code {prefix}_{14 random lowercase-alphanumeric chars}},
 * e.g. {@code act_4f9c2a7b3d1e08}. Assigned once, in a JPA {@code @PrePersist} callback, on
 * every entity that exposes an id through the API.
 */
public final class PrefixedId {

	private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
	private static final int SUFFIX_LENGTH = 14;
	private static final SecureRandom RANDOM = new SecureRandom();

	private PrefixedId() {
	}

	public static String generate(String prefix) {
		StringBuilder suffix = new StringBuilder(SUFFIX_LENGTH);
		for (int i = 0; i < SUFFIX_LENGTH; i++) {
			suffix.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
		}
		return prefix + "_" + suffix;
	}
}
