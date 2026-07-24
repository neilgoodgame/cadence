package com.cadence.api.races.dto;

public record RaceUpdateRequest(
		String name,
		String date,
		String sport,
		Double distanceKm,
		String goalTime,
		String resultTime,
		String activityId,
		String url,
		String resultsUrl,
		String notes
) {}
