package com.cadence.api.workouts;

import com.cadence.api.common.jpa.LowercaseEnumConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class MatchScanDurationBasisConverter extends LowercaseEnumConverter<MatchScanDurationBasis> {

	public MatchScanDurationBasisConverter() {
		super(MatchScanDurationBasis.class);
	}
}
