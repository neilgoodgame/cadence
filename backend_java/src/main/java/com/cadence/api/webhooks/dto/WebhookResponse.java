package com.cadence.api.webhooks.dto;

import com.cadence.api.webhooks.WebhookStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** {@code secret} is omitted entirely (not serialized as null) outside of the create response - it's returned once, not listed. */
public record WebhookResponse(
		String id, String url, WebhookStatus status, List<String> events,
		@JsonInclude(JsonInclude.Include.NON_NULL) String secret) {

	public static WebhookResponse withoutSecret(String id, String url, WebhookStatus status, List<String> events) {
		return new WebhookResponse(id, url, status, events, null);
	}
}
