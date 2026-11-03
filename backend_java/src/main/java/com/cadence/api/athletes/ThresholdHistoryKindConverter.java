package com.cadence.api.athletes;

import com.cadence.api.common.jpa.LowercaseEnumConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class ThresholdHistoryKindConverter extends LowercaseEnumConverter<ThresholdHistoryKind> {

	public ThresholdHistoryKindConverter() {
		super(ThresholdHistoryKind.class);
	}
}
