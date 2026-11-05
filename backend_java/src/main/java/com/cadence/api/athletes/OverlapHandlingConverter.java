package com.cadence.api.athletes;

import com.cadence.api.common.jpa.LowercaseEnumConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class OverlapHandlingConverter extends LowercaseEnumConverter<OverlapHandling> {

	public OverlapHandlingConverter() {
		super(OverlapHandling.class);
	}
}
