package com.cadence.api.webhooks;

import com.cadence.api.common.jpa.LowercaseEnumConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class WebhookStatusConverter extends LowercaseEnumConverter<WebhookStatus> {

	public WebhookStatusConverter() {
		super(WebhookStatus.class);
	}
}
