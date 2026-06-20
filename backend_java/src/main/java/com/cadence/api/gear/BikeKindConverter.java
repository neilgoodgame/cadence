package com.cadence.api.gear;

import com.cadence.api.common.jpa.LowercaseEnumConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class BikeKindConverter extends LowercaseEnumConverter<BikeKind> {

	public BikeKindConverter() {
		super(BikeKind.class);
	}
}
