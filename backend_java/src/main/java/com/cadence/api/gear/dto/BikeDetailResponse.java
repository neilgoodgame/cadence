package com.cadence.api.gear.dto;

import com.cadence.api.gear.BikeKind;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.util.List;

public record BikeDetailResponse(@JsonUnwrapped BikeSummary bike, List<ComponentResponse> components) {

	public record BikeSummary(
			String id, String athleteId, String name, BikeKind kind, String groupset,
			int distanceKm, double hours, int rides) {
	}
}
