package com.cadence.api.gear.dto;

import java.time.LocalDate;

public record ShoeResponse(
		String id, String athleteId, String shoeModelVersionId, String manufacturer, String model, String version,
		String colourway, String name, String image, String role, int km, int limitKm, LocalDate since) {
}
