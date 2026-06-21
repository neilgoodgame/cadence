package com.cadence.api.workouts;

import com.cadence.api.common.jpa.LowercaseEnumConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class StepKindConverter extends LowercaseEnumConverter<StepKind> {

	public StepKindConverter() {
		super(StepKind.class);
	}
}
