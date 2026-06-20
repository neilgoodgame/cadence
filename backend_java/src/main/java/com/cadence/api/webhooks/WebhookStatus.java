package com.cadence.api.webhooks;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum WebhookStatus {
	ACTIVE, DISABLED;

	@JsonValue
	public String wireValue() {
		return name().toLowerCase();
	}

	@JsonCreator
	public static WebhookStatus fromWireValue(String value) {
		return WebhookStatus.valueOf(value.toUpperCase());
	}
}
