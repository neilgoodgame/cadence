package com.cadence.api.athletes;

import com.cadence.api.common.jpa.LowercaseEnumConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class RunningPowerSourceConverter extends LowercaseEnumConverter<RunningPowerSource> {

	public RunningPowerSourceConverter() {
		super(RunningPowerSource.class);
	}
}
