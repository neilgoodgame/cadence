package com.cadence.api.webhooks;

import com.cadence.api.common.jpa.LowercaseEnumConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class WebhookDeliveryStatusConverter extends LowercaseEnumConverter<WebhookDeliveryStatus> {

	public WebhookDeliveryStatusConverter() {
		super(WebhookDeliveryStatus.class);
	}
}
