package com.cadence.api.activities;

import com.cadence.api.common.jpa.LowercaseEnumConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class DurationCurveMetricConverter extends LowercaseEnumConverter<DurationCurveMetric> {

	public DurationCurveMetricConverter() {
		super(DurationCurveMetric.class);
	}
}
