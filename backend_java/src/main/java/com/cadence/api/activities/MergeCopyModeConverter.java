package com.cadence.api.activities;

import com.cadence.api.common.jpa.LowercaseEnumConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class MergeCopyModeConverter extends LowercaseEnumConverter<MergeCopyMode> {

	public MergeCopyModeConverter() {
		super(MergeCopyMode.class);
	}
}
