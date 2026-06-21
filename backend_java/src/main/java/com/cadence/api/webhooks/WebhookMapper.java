package com.cadence.api.webhooks;

import com.cadence.api.webhooks.dto.WebhookResponse;
import org.springframework.stereotype.Component;

@Component
public class WebhookMapper {

	public WebhookResponse toResponse(Webhook webhook) {
		return WebhookResponse.withoutSecret(webhook.getId(), webhook.getUrl(), webhook.getStatus(), webhook.getEvents());
	}

	public WebhookResponse toCreatedResponse(Webhook webhook) {
		return new WebhookResponse(webhook.getId(), webhook.getUrl(), webhook.getStatus(), webhook.getEvents(), webhook.getSecret());
	}
}
