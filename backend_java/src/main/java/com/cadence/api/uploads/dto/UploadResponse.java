package com.cadence.api.uploads.dto;

import com.cadence.api.uploads.UploadStatus;
import java.time.Instant;

public record UploadResponse(
		String id, UploadStatus status, Double progress, String filename, String activityId, UploadError error,
		Instant receivedAt, Instant completedAt) {

	public String object() {
		return "upload";
	}
}
