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
		Integer targetPower,
		// Readable without authentication via /v1/public/races/{id} when true.
		boolean isPublic,
		// A races only - see Race.java's own field comments. Appended at the end rather than
		// placed near blockStart/blockWeeks (the existing, read-only *computed* fields these are
		// easily confused with) to keep this a non-breaking addition to existing positional
		// construction (see backend_java/CLAUDE.md's record-DTO gotcha).
		Integer blockWeeksRequested,
		Integer baseWeeksRequested,
		Integer buildWeeksRequested,
		Integer peakWeeksRequested,
		Integer taperWeeksRequested
) {}
