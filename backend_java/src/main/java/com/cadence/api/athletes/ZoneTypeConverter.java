package com.cadence.api.athletes;

import com.cadence.api.common.jpa.LowercaseEnumConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class ZoneTypeConverter extends LowercaseEnumConverter<ZoneType> {

	public ZoneTypeConverter() {
		super(ZoneType.class);
	}
}
