package com.cadence.api.workouts;

import com.cadence.api.common.jpa.LowercaseEnumConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class StepEndTypeConverter extends LowercaseEnumConverter<StepEndType> {

	public StepEndTypeConverter() {
		super(StepEndType.class);
	}
}
