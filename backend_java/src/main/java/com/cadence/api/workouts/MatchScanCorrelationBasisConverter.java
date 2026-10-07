package com.cadence.api.workouts;

import com.cadence.api.common.jpa.LowercaseEnumConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class MatchScanCorrelationBasisConverter extends LowercaseEnumConverter<MatchScanCorrelationBasis> {

	public MatchScanCorrelationBasisConverter() {
		super(MatchScanCorrelationBasis.class);
	}
}
