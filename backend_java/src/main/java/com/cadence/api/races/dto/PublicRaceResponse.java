package com.cadence.api.races.dto;

import com.cadence.api.courses.CourseSections;
import java.util.List;

/** GET /v1/public/races/{id} - the unauthenticated, read-only view of a race its owner has marked
 * public, for sharing a result with friends and family. Deliberately a narrow allowlist rather
 * than RaceResponse minus a few fields: no notes, priority, training block, target power or
 * anything else from the athlete's training, so a field later added to RaceResponse never becomes
 * public by accident. */
public record PublicRaceResponse(
		String id, String athleteName, String name, String date, String sport, Double distanceKm,
		String goalTime, String resultTime, String url, String resultsUrl,
		// Null when no activity is linked. Only what the result card shows alongside the time -
		// the linked activity itself stays private unless it's separately shared.
		ActivityStats activity,
		// Null when no course is linked.
		Course course) {

	public record ActivityStats(Integer avgHr, Integer avgPower, Double avgAirTemp, Integer avgHumidity) {}

	/** The course's shape and elevation - nothing about who made it (athlete id, source
	 * activity, GPX filename) or their other races on it. */
	public record Course(
			String name, String locationName, Double distanceKm, Integer elevationGainM, Integer elevationLossM,
			Integer elevationMaxM, Integer elevationMinM, boolean isLoop, List<List<Double>> routePolyline,
			List<List<Double>> elevationProfile, String elevationQuality, List<CourseSections.Section> sections) {}
}
