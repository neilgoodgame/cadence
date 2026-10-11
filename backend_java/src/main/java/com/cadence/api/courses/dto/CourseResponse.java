package com.cadence.api.courses.dto;

import com.cadence.api.courses.Climb;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record CourseResponse(
		String id, String athleteId, String name, String locationName, Double startLat, Double startLng,
		Double distanceKm, Integer elevationGainM, Integer elevationLossM, Integer elevationMaxM, Integer elevationMinM,
		boolean isLoop, String source, String sourceActivityId, String gpxFilename, List<List<Double>> routePolyline,
		List<List<Double>> elevationProfile, List<Climb> climbs, Instant createdAt, long raceCount, List<RaceSummary> races) {

	public record RaceSummary(String id, String name, LocalDate date, String priority, Integer resultS) {}
}
