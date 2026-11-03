package com.cadence.api.athletes;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** How a ThresholdHistory row's value was determined. DECAYED isn't written anywhere yet
 * (issue #480's decay core, #485) - this type exists ahead of that so a decayed row can
 * already be told apart from a manual one (both have no sourceActivity) once decay lands. */
public enum ThresholdHistoryKind {
	MEASURED, MANUAL, DECAYED;

	@JsonValue
	public String wireValue() {
		return name().toLowerCase();
	}

	@JsonCreator
	public static ThresholdHistoryKind fromWireValue(String value) {
		return ThresholdHistoryKind.valueOf(value.toUpperCase());
	}
}
