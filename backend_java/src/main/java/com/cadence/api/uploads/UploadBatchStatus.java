package com.cadence.api.uploads;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum UploadBatchStatus {
	UNPACKING, PROCESSING, COMPLETED, FAILED;

	@JsonValue
	public String wireValue() {
		return name().toLowerCase();
	}

	@JsonCreator
	public static UploadBatchStatus fromWireValue(String value) {
		return UploadBatchStatus.valueOf(value.toUpperCase());
	}
}
