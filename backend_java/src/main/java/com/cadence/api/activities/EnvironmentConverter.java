package com.cadence.api.activities;

import com.cadence.api.common.jpa.LowercaseEnumConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class EnvironmentConverter extends LowercaseEnumConverter<Environment> {

	public EnvironmentConverter() {
		super(Environment.class);
	}
}
