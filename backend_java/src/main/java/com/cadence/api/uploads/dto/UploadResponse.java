package com.cadence.api.uploads.dto;

import com.cadence.api.uploads.UploadStatus;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

public record UploadResponse(
		String id, UploadStatus status, Double progress, String filename, String activityId, UploadError error,
		Instant receivedAt, Instant completedAt) {

	@JsonProperty("object")
	public String object() {
		return "upload";
	}
}
