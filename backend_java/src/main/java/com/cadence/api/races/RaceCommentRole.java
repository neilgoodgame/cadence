package com.cadence.api.races;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum RaceCommentRole {
	ATHLETE, COACH;

	@JsonValue
	public String wireValue() {
		return name().toLowerCase();
	}

	@JsonCreator
	public static RaceCommentRole fromWireValue(String value) {
		return RaceCommentRole.valueOf(value.toUpperCase());
	}
}
