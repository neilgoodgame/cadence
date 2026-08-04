package com.cadence.api.imports;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum ImportStatus {
	QUEUED, PROCESSING, READY, FAILED;

	@JsonValue
	public String wireValue() {
		return name().toLowerCase();
	}

	@JsonCreator
	public static ImportStatus fromWireValue(String value) {
		return ImportStatus.valueOf(value.toUpperCase());
	}
}
