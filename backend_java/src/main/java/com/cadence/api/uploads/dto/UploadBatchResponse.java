package com.cadence.api.uploads.dto;

import com.cadence.api.uploads.UploadBatchStatus;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;

public record UploadBatchResponse(
		String id, UploadBatchStatus status, String filename, double progress, UploadBatchCounts counts,
		List<UploadResponse> uploads, Instant receivedAt, Instant completedAt) {

	@JsonProperty("object")
	public String object() {
		return "upload_batch";
	}
}
