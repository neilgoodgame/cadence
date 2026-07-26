package com.cadence.api.workouts;

import com.cadence.api.common.jpa.LowercaseEnumConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class TargetTypeConverter extends LowercaseEnumConverter<TargetType> {

	public TargetTypeConverter() {
		super(TargetType.class);
	}
}
