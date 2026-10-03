package com.cadence.api.athletes;

import com.cadence.api.common.jpa.LowercaseEnumConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class ThresholdSuggestionDismissalKindConverter extends LowercaseEnumConverter<ThresholdSuggestionDismissal.Kind> {

	public ThresholdSuggestionDismissalKindConverter() {
		super(ThresholdSuggestionDismissal.Kind.class);
	}
}
