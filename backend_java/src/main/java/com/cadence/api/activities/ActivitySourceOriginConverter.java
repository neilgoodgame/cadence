package com.cadence.api.activities;

import com.cadence.api.common.jpa.LowercaseEnumConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class ActivitySourceOriginConverter extends LowercaseEnumConverter<ActivitySourceOrigin> {

	public ActivitySourceOriginConverter() {
		super(ActivitySourceOrigin.class);
	}
}
