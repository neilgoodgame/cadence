package com.cadence.api.garmin.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * {@code apiKey} is required the first time and optional afterwards (omit it to leave the stored
 * key alone while editing the email or expiry). {@code email} defaults to the athlete's own
 * login email when blank. {@code syncLimit} (1-100) is how many activities one sync pulls;
 * omit it to leave it alone (50 for a new connection).
 */
public record GarminConnectionRequest(
		@Size(min = 8, max = 512) String apiKey,
		@Email @Size(max = 320) String email,
		LocalDate keyExpiresOn,
		@Min(1) @Max(100) Integer syncLimit) {
}
