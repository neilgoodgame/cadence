package com.cadence.api.athletes;

import com.cadence.api.common.jpa.LowercaseEnumConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class FtpCalculationMethodConverter extends LowercaseEnumConverter<FtpCalculationMethod> {

	public FtpCalculationMethodConverter() {
		super(FtpCalculationMethod.class);
	}
}
