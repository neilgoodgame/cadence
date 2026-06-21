package com.cadence.api.uploads;

import com.cadence.api.common.jpa.LowercaseEnumConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class OnDuplicateConverter extends LowercaseEnumConverter<OnDuplicate> {

	public OnDuplicateConverter() {
		super(OnDuplicate.class);
	}
}
