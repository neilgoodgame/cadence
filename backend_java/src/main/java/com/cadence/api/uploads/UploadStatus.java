package com.cadence.api.uploads;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum UploadStatus {
	QUEUED, PROCESSING, READY, FAILED, DUPLICATE, SKIPPED;

	@JsonValue
	public String wireValue() {
		return name().toLowerCase();
	}

	@JsonCreator
	public static UploadStatus fromWireValue(String value) {
		return UploadStatus.valueOf(value.toUpperCase());
	}
}
