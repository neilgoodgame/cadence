package com.cadence.api.garmin.dto;

import java.time.LocalDate;

/** Both optional. {@code limit} caps this run (1 to the server's configured max); {@code since}
 * overrides the default window (day after the last sync, or 30 days on the first). */
public record GarminSyncRequest(Integer limit, LocalDate since) {
}
