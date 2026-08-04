package com.cadence.api.export;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum ExportStatus {
	QUEUED, PROCESSING, READY, FAILED;

	@JsonValue
	public String wireValue() {
		return name().toLowerCase();
	}

	@JsonCreator
	public static ExportStatus fromWireValue(String value) {
		return ExportStatus.valueOf(value.toUpperCase());
	}
}
