package com.cadence.api.imports.dto;

import com.cadence.api.imports.ImportStatus;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

public record ImportJobResponse(
		String id, ImportStatus status, String currentStep, Integer totalItems, int processedItems, ImportCounts counts,
		String errorMessage, Instant createdAt, Instant completedAt) {

	@JsonProperty("object")
	public String object() {
		return "import";
	}
}
