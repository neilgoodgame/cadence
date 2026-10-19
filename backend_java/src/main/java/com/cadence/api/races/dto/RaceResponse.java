package com.cadence.api.races.dto;

import java.time.LocalDate;

public record RaceResponse(
		String id,
		String athleteId,
		String name,
		String date,
		String sport,
		String priority,
		String courseId,
		Double distanceKm,
		String goalTime,
		String resultTime,
		String activityId,
		String url,
		String resultsUrl,
		String notes,
		String parentRaceId,
		LocalDate blockStart,
		Integer blockWeeks,
		String keySessionTagId,
		Integer targetPower
) {}
