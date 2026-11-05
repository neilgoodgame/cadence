package com.cadence.api.activities;

import com.cadence.api.common.jpa.LowercaseEnumConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class OverlapActionConverter extends LowercaseEnumConverter<OverlapAction> {

	public OverlapActionConverter() {
		super(OverlapAction.class);
	}
}
