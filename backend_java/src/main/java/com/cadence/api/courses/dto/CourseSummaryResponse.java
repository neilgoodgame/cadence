package com.cadence.api.courses.dto;

import java.time.Instant;

/** The shape returned by GET /v1/courses - full route/elevation detail is on
 * GET /v1/courses/{id} only (CourseResponse). */
public record CourseSummaryResponse(
		String id, String athleteId, String name, String locationName, Double distanceKm, String source,
		Instant createdAt, long raceCount) {
}
