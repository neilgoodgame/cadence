package com.cadence.api.garmin.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * {@code apiKey} is required the first time and optional afterwards (omit it to leave the stored
 * key alone while editing the email or expiry). {@code email} defaults to the athlete's own
 * login email when blank.
 */
public record GarminConnectionRequest(
		@Size(min = 8, max = 512) String apiKey,
		@Email @Size(max = 320) String email,
		LocalDate keyExpiresOn) {
}
