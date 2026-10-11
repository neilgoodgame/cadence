package com.cadence.api.courses;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum CourseSource {
	GPX, ACTIVITY;

	@JsonValue
	public String wireValue() {
		return name().toLowerCase();
	}

	@JsonCreator
	public static CourseSource fromWireValue(String value) {
		return CourseSource.valueOf(value.toUpperCase());
	}
}
