package com.cadence.api.garmin.dto;

import com.cadence.api.athletes.OverlapHandling;
import java.time.LocalDate;

/**
 * All optional. {@code limit} caps this run (1-100; defaults to the athlete's saved sync limit) and
 * {@code since} overrides the default window (day after the last sync, or 30 days on the first).
 * {@code latest} syncs only Garmin's newest activity; only then may the session details
 * (weights, fluids, shoe) be given, exactly as when importing a single file. {@code onOverlap}
 * overrides the athlete's own overlap-handling default for this sync's batch, same as {@code
 * on_overlap} on a manual .zip upload.
 */
public record GarminSyncRequest(Integer limit, LocalDate since, Boolean latest, Double weightBeforeKg,
		Double weightAfterKg, Integer fluidsMl, String shoeId, OverlapHandling onOverlap) {

	public boolean isLatest() {
		return Boolean.TRUE.equals(latest);
	}

	public boolean hasSessionDetails() {
		return weightBeforeKg != null || weightAfterKg != null || fluidsMl != null
				|| (shoeId != null && !shoeId.isBlank());
	}
}
