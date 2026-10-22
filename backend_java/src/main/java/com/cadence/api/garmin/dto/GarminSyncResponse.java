package com.cadence.api.garmin.dto;

import com.cadence.api.uploads.dto.UploadBatchResponse;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * {@code found} is how many activities Garmin has in the window, {@code alreadySynced} how many
 * of those were pulled by an earlier sync (skipped without downloading), {@code requested} how
 * many this run tried to import (at most the cap), {@code failed} how many couldn't be
 * downloaded. {@code truncated} means more remain - sync again. {@code batch} is null when there
 * was nothing new to import; otherwise poll it like any other batch upload.
 */
public record GarminSyncResponse(String id, int found, int alreadySynced, int requested, int failed,
		boolean truncated, UploadBatchResponse batch) {

	@JsonProperty("object")
	public String object() {
		return "garmin_sync";
	}
}
