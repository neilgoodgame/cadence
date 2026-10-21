package com.cadence.api.races.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record RaceCreateRequest(
		@NotBlank String name,
		@NotNull String date,
		String sport,
		Double distanceKm,
		String goalTime,
		String resultTime,
		String activityId,
		// http(s) only - rendered as a raw <a href> on both the authenticated and (since public
		// race sharing) unauthenticated race pages, so a javascript:/data: scheme here would be a
		// stored XSS reachable by anyone with a shared link, not just the owner.
		@Pattern(regexp = "^$|^https?://\\S+$", message = "Must be an http(s) URL.") String url,
		@Pattern(regexp = "^$|^https?://\\S+$", message = "Must be an http(s) URL.") String resultsUrl,
		String notes,
		String priority,
		String courseId,
		String keySessionTagId,
		Integer targetPower,
		Integer blockWeeksRequested,
		Integer baseWeeksRequested,
		Integer buildWeeksRequested,
		Integer peakWeeksRequested,
		Integer taperWeeksRequested
) {}
