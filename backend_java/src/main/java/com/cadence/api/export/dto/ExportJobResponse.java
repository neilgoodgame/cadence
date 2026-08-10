package com.cadence.api.export.dto;

import com.cadence.api.export.ExportStatus;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

public record ExportJobResponse(
		String id, ExportStatus status, String currentStep, Long fileSizeBytes, String errorMessage, Instant createdAt,
		Instant completedAt) {

	@JsonProperty("object")
	public String object() {
		return "export";
	}
}
