package com.cadence.api.activities;

import com.cadence.api.common.jpa.LowercaseEnumConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class TagOriginConverter extends LowercaseEnumConverter<TagOrigin> {

	public TagOriginConverter() {
		super(TagOrigin.class);
	}
}
