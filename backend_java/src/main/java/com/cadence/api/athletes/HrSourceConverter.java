package com.cadence.api.athletes;

import com.cadence.api.common.jpa.LowercaseEnumConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class HrSourceConverter extends LowercaseEnumConverter<HrSource> {

	public HrSourceConverter() {
		super(HrSource.class);
	}
}
