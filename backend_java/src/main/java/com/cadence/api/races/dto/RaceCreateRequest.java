package com.cadence.api.races.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record RaceCreateRequest(
		@NotBlank String name,
		@NotNull String date,
		String sport,
		Double distanceKm,
		String goalTime,
		String resultTime,
		String activityId,
		String url,
		String resultsUrl,
		String notes
) {}
