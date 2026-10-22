package com.cadence.api.garmin.dto;

import com.cadence.api.garmin.GarminConnection;
import com.cadence.api.garmin.GarminSyncedActivity;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Never carries the API key, in any form. {@code lastRunAt} is when a sync last ran (successful
 * or not); {@code lastActivity} is the newest Garmin activity ever pulled (by its start time).
 */
public record GarminConnectionResponse(
		boolean connected, String email, LocalDate keyExpiresOn, Instant lastSyncedAt, Instant lastRunAt,
		LastActivity lastActivity, Integer syncLimit) {

	public record LastActivity(long garminActivityId, Instant startTime) {
	}

	public static GarminConnectionResponse notConnected() {
		return new GarminConnectionResponse(false, null, null, null, null, null, null);
	}

	public static GarminConnectionResponse from(GarminConnection connection, Instant lastRunAt,
			GarminSyncedActivity lastActivity) {
		return new GarminConnectionResponse(true, connection.getGarminEmail(), connection.getKeyExpiresOn(),
				connection.getLastSyncedAt(), lastRunAt,
				lastActivity == null ? null
						: new LastActivity(lastActivity.getGarminActivityId(), lastActivity.getStartTime()),
				connection.getSyncLimit());
	}
}
