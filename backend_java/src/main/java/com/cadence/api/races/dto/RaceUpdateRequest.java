package com.cadence.api.races.dto;

import jakarta.validation.constraints.Pattern;

public record RaceUpdateRequest(
		String name,
		String date,
		String sport,
		Double distanceKm,
		String goalTime,
		String resultTime,
		String activityId,
		// http(s) only - see RaceCreateRequest's url field for why.
		@Pattern(regexp = "^$|^https?://\\S+$", message = "Must be an http(s) URL.") String url,
		@Pattern(regexp = "^$|^https?://\\S+$", message = "Must be an http(s) URL.") String resultsUrl,
		String notes,
		String priority,
		String courseId,
		String keySessionTagId,
		Integer targetPower,
		Boolean isPublic,
		Integer blockWeeksRequested,
		Integer baseWeeksRequested,
		Integer buildWeeksRequested,
		Integer peakWeeksRequested,
		Integer taperWeeksRequested
) {}
