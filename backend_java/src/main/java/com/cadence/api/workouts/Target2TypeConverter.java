package com.cadence.api.workouts;

import com.cadence.api.common.jpa.LowercaseEnumConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class Target2TypeConverter extends LowercaseEnumConverter<Target2Type> {

	public Target2TypeConverter() {
		super(Target2Type.class);
	}
}
