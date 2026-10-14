package com.cadence.api.export.dto;

import com.cadence.api.courses.Climb;
import java.time.Instant;
import java.util.List;

/** gpxFile is a default_storage-style path, not a DB blob (see Course.gpxFile) - the original
 * upload is read and base64-embedded here so the export is self-contained. Null for a course
 * with no original file, e.g. source=activity. */
public record CourseExportEntry(
		String id, String name, String locationName, Double startLat, Double startLng, Double distanceKm,
		Integer elevationGainM, Integer elevationLossM, Integer elevationMaxM, Integer elevationMinM, boolean isLoop,
		String source, String sourceActivityId, String gpxFilename, String gpxBase64, List<List<Double>> routePolyline,
		List<List<Double>> elevationProfile, List<Climb> climbs, Instant createdAt, String elevationQuality,
		List<Double> elevationSeries) {
}
