package com.cadence.api.uploads.dto;

import com.cadence.api.uploads.UploadBatchStatus;
import java.time.Instant;
import java.util.List;

public record UploadBatchResponse(
		String id, UploadBatchStatus status, String filename, double progress, UploadBatchCounts counts,
		List<UploadResponse> uploads, Instant receivedAt, Instant completedAt) {

	public String object() {
		return "upload_batch";
	}
}
