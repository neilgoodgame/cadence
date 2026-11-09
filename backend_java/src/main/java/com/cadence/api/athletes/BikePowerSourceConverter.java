package com.cadence.api.athletes;

import com.cadence.api.common.jpa.LowercaseEnumConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class BikePowerSourceConverter extends LowercaseEnumConverter<BikePowerSource> {

	public BikePowerSourceConverter() {
		super(BikePowerSource.class);
	}
}
