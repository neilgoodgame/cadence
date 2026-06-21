package com.cadence.api.gear;

import com.cadence.api.common.jpa.LowercaseEnumConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class ServiceActionConverter extends LowercaseEnumConverter<ServiceAction> {

	public ServiceActionConverter() {
		super(ServiceAction.class);
	}
}
