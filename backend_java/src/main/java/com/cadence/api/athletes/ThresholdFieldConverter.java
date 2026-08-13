package com.cadence.api.athletes;

import com.cadence.api.common.jpa.LowercaseEnumConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class ThresholdFieldConverter extends LowercaseEnumConverter<ThresholdField> {

	public ThresholdFieldConverter() {
		super(ThresholdField.class);
	}
}
