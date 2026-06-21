package com.cadence.api.activities;

import com.cadence.api.common.jpa.LowercaseEnumConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class DistanceSourceConverter extends LowercaseEnumConverter<DistanceSource> {

	public DistanceSourceConverter() {
		super(DistanceSource.class);
	}
}
