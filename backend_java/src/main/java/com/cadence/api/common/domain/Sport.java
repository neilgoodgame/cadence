package com.cadence.api.common.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Shared across activities and workouts; workouts restrict this to {@code BIKE}/{@code RUN} at the
 * service layer. {@code MULTISPORT} and {@code TRANSITION} only occur on activities created from a
 * multisport FIT file: the parent spans the whole file and each leg (including transitions) is a
 * child activity linked via {@code parent_activity_id}.
 */
public enum Sport {
	BIKE, RUN, SWIM, WALK, MULTISPORT, TRANSITION;

	@JsonValue
	public String wireValue() {
		return name().toLowerCase();
	}

	@JsonCreator
	public static Sport fromWireValue(String value) {
		return Sport.valueOf(value.toUpperCase());
	}
}
