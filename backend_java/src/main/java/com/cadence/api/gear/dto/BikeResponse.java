package com.cadence.api.gear.dto;

import com.cadence.api.gear.BikeKind;

public record BikeResponse(
		String id, String athleteId, String name, BikeKind kind, String groupset,
		int distanceKm, double hours, int rides, int components) {
}
