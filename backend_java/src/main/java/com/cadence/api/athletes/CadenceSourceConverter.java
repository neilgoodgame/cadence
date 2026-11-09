package com.cadence.api.athletes;

import com.cadence.api.common.jpa.LowercaseEnumConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class CadenceSourceConverter extends LowercaseEnumConverter<CadenceSource> {

	public CadenceSourceConverter() {
		super(CadenceSource.class);
	}
}
