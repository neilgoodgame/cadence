package com.cadence.api.webhooks;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum WebhookDeliveryStatus {
	PENDING, SUCCEEDED, FAILED;

	@JsonValue
	public String wireValue() {
		return name().toLowerCase();
	}

	@JsonCreator
	public static WebhookDeliveryStatus fromWireValue(String value) {
		return WebhookDeliveryStatus.valueOf(value.toUpperCase());
	}
}
