package com.cadence.api.activities;

import com.cadence.api.common.jpa.LowercaseEnumConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class BestEffortKindConverter extends LowercaseEnumConverter<BestEffortKind> {

	public BestEffortKindConverter() {
		super(BestEffortKind.class);
	}
}
