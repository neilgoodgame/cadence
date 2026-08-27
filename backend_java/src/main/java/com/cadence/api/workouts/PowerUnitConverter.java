package com.cadence.api.workouts;

import com.cadence.api.common.jpa.LowercaseEnumConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class PowerUnitConverter extends LowercaseEnumConverter<PowerUnit> {

	public PowerUnitConverter() {
		super(PowerUnit.class);
	}
}
