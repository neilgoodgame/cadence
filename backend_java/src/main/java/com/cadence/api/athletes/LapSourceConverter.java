package com.cadence.api.athletes;

import com.cadence.api.common.jpa.LowercaseEnumConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class LapSourceConverter extends LowercaseEnumConverter<LapSource> {

	public LapSourceConverter() {
		super(LapSource.class);
	}
}
