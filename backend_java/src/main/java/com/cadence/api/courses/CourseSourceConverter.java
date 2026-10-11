package com.cadence.api.courses;

import com.cadence.api.common.jpa.LowercaseEnumConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class CourseSourceConverter extends LowercaseEnumConverter<CourseSource> {

	public CourseSourceConverter() {
		super(CourseSource.class);
	}
}
