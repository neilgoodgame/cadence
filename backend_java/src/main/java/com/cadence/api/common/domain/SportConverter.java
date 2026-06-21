package com.cadence.api.common.domain;

import com.cadence.api.common.jpa.LowercaseEnumConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class SportConverter extends LowercaseEnumConverter<Sport> {

	public SportConverter() {
		super(Sport.class);
	}
}
