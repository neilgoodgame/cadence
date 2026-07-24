package com.cadence.api.races.dto;

public record RaceResponse(
		String id,
		String athleteId,
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
