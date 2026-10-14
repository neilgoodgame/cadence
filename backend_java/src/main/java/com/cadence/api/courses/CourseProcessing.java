package com.cadence.api.courses;

import com.cadence.api.activities.Record;
import io.jenetics.jpx.GPX;
import io.jenetics.jpx.Length;
import io.jenetics.jpx.WayPoint;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * GPX/track processing for Course: distance, elevation smoothing, the stored elevation series
 * sections are calculated from, route simplification and elevation-profile resampling. Mirrors
 * the Python backend's courses/processing.py function-for-function - a parity test (ported
 * fixtures) checks the two backends agree on distance/gain/loss within 0.5%, per README §3.6.
 */
public final class CourseProcessing {

	public static final long MAX_GPX_BYTES = 10 * 1024 * 1024;
	public static final int MIN_TRACK_POINTS = 50;

	private static final double EARTH_RADIUS_KM = 6371.0088;
	private static final double ELEVATION_SMOOTH_WINDOW_M = 50.0;
	private static final double ELEVATION_NOISE_THRESHOLD_M = 2.0;
	private static final int ROUTE_SIMPLIFY_MAX_POINTS = 500;
	private static final int ELEVATION_PROFILE_POINTS = 200;
	private static final double LOOP_THRESHOLD_M = 200.0;

	public static class GpxProcessingException extends RuntimeException {
		public GpxProcessingException(String message) {
			super(message);
		}
	}

	public record TrackPoint(double lat, double lng, Double elevation, double distanceKm) {}

	public record ProcessedTrack(
			double startLat, double startLng, double distanceKm, int elevationGainM, int elevationLossM,
			Integer elevationMaxM, Integer elevationMinM, boolean isLoop, List<List<Double>> routePolyline,
			List<List<Double>> elevationProfile, String elevationQuality, List<Double> elevationSeries) {}

	private CourseProcessing() {
	}

	public static List<TrackPoint> parseGpxTrack(byte[] rawBytes) {
		if (rawBytes.length > MAX_GPX_BYTES) {
			throw new GpxProcessingException("GPX file exceeds the 10 MB limit.");
		}

		GPX gpx;
		try {
			gpx = GPX.Reader.of(GPX.Reader.Mode.LENIENT).read(new ByteArrayInputStream(rawBytes));
		}
		catch (Exception e) {
			throw new GpxProcessingException("The GPX file could not be parsed.");
		}

		List<WayPoint> points = new ArrayList<>();
		gpx.tracks().forEach(t -> t.segments().forEach(s -> points.addAll(s.getPoints())));
		if (points.isEmpty()) {
			gpx.routes().forEach(r -> points.addAll(r.getPoints()));
		}
		if (points.size() < MIN_TRACK_POINTS) {
			throw new GpxProcessingException("GPX file has fewer than " + MIN_TRACK_POINTS + " usable track points.");
		}

		List<TrackPoint> trackPoints = new ArrayList<>(points.size());
		double cumulativeKm = 0.0;
		WayPoint prev = null;
		for (WayPoint p : points) {
			double lat = p.getLatitude().toDegrees();
			double lng = p.getLongitude().toDegrees();
			if (prev != null) {
				cumulativeKm += haversineKm(prev.getLatitude().toDegrees(), prev.getLongitude().toDegrees(), lat, lng);
			}
			Double elevation = p.getElevation().map(e -> e.to(Length.Unit.METER)).orElse(null);
			trackPoints.add(new TrackPoint(lat, lng, elevation, cumulativeKm));
			prev = p;
		}
		return trackPoints;
	}

	/** Builds TrackPoints from an activity's recorded lat/lng/altitude/distanceKm - reuses the
	 * already-recorded cumulative distance rather than re-deriving it, and drops samples where
	 * distance didn't advance (the activity was paused). */
	public static List<TrackPoint> trackPointsFromActivity(List<Record> records) {
		List<TrackPoint> trackPoints = new ArrayList<>();
		Double lastDistance = null;
		for (Record r : records) {
			if (r.getLat() == null || r.getLng() == null) continue;
			Double d = r.getDistanceKm();
			if (lastDistance != null && d != null && d <= lastDistance) continue;
			trackPoints.add(new TrackPoint(r.getLat(), r.getLng(), r.getAltitude(), d != null ? d : 0.0));
			lastDistance = d;
		}
		if (trackPoints.size() < MIN_TRACK_POINTS) {
			throw new GpxProcessingException("Activity has fewer than " + MIN_TRACK_POINTS + " usable GPS points.");
		}
		return trackPoints;
	}

	/** Centered moving average over a 50m distance window, using a two-pointer sliding window
	 * since distanceKm is monotonically non-decreasing. */
	static Double[] smoothedElevations(List<TrackPoint> points) {
		int n = points.size();
		Double[] smoothed = new Double[n];
		double halfWindowKm = (ELEVATION_SMOOTH_WINDOW_M / 2) / 1000.0;
		int left = 0;
		int right = 0;
		for (int i = 0; i < n; i++) {
			double centerKm = points.get(i).distanceKm();
			double lo = centerKm - halfWindowKm;
			double hi = centerKm + halfWindowKm;
			while (left < n && points.get(left).distanceKm() < lo) left++;
			if (right < left) right = left;
			while (right < n && points.get(right).distanceKm() <= hi) right++;
			double sum = 0;
			int count = 0;
			for (int k = left; k < right; k++) {
				Double e = points.get(k).elevation();
				if (e != null) {
					sum += e;
					count++;
				}
			}
			smoothed[i] = count > 0 ? sum / count : null;
		}
		return smoothed;
	}

	/** Counts gain/loss only once the smoothed elevation has moved >= 2m from the last
	 * confirmed baseline, so GPS/barometric noise under that doesn't accumulate. */
	static int[] gainLoss(Double[] smoothed) {
		List<Double> values = new ArrayList<>();
		for (Double v : smoothed) if (v != null) values.add(v);
		if (values.isEmpty()) return new int[] {0, 0};

		double gain = 0;
		double loss = 0;
		double baseline = values.get(0);
		for (int i = 1; i < values.size(); i++) {
			double v = values.get(i);
			if (v - baseline >= ELEVATION_NOISE_THRESHOLD_M) {
				gain += v - baseline;
				baseline = v;
			}
			else if (baseline - v >= ELEVATION_NOISE_THRESHOLD_M) {
				loss += baseline - v;
				baseline = v;
			}
		}
		return new int[] {Math.round((float) gain), Math.round((float) loss)};
	}

	private static double perpendicularDistance(double[] point, double[] start, double[] end) {
		double x = point[0];
		double y = point[1];
		double x1 = start[0];
		double y1 = start[1];
		double x2 = end[0];
		double y2 = end[1];
		if (x1 == x2 && y1 == y2) {
			return Math.hypot(x - x1, y - y1);
		}
		double t = ((x - x1) * (x2 - x1) + (y - y1) * (y2 - y1)) / ((x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1));
		t = Math.max(0.0, Math.min(1.0, t));
		double projX = x1 + t * (x2 - x1);
		double projY = y1 + t * (y2 - y1);
		return Math.hypot(x - projX, y - projY);
	}

	private static List<double[]> douglasPeucker(List<double[]> coords, double epsilon) {
		if (coords.size() < 3) return coords;
		double[] start = coords.get(0);
		double[] end = coords.get(coords.size() - 1);
		double maxDist = 0.0;
		int index = 0;
		for (int i = 1; i < coords.size() - 1; i++) {
			double d = perpendicularDistance(coords.get(i), start, end);
			if (d > maxDist) {
				index = i;
				maxDist = d;
			}
		}
		if (maxDist > epsilon) {
			List<double[]> left = douglasPeucker(coords.subList(0, index + 1), epsilon);
			List<double[]> right = douglasPeucker(coords.subList(index, coords.size()), epsilon);
			List<double[]> result = new ArrayList<>(left.subList(0, left.size() - 1));
			result.addAll(right);
			return result;
		}
		return List.of(start, end);
	}

	static List<List<Double>> simplifyRoute(List<TrackPoint> points) {
		List<double[]> coords = points.stream().map(p -> new double[] {p.lat(), p.lng()}).toList();
		List<double[]> simplified = coords;
		if (coords.size() > ROUTE_SIMPLIFY_MAX_POINTS) {
			double epsilon = 0.00001;
			for (int i = 0; i < 30; i++) {
				simplified = douglasPeucker(coords, epsilon);
				if (simplified.size() <= ROUTE_SIMPLIFY_MAX_POINTS) break;
				epsilon *= 1.5;
			}
		}
		return simplified.stream().map(c -> List.of(round6(c[0]), round6(c[1]))).toList();
	}

	private static List<List<Double>> elevationProfile(List<TrackPoint> points, Double[] smoothed) {
		double totalKm = points.get(points.size() - 1).distanceKm();
		if (totalKm <= 0) return List.of();

		List<List<Double>> profile = new ArrayList<>();
		int idx = 0;
		for (int i = 0; i <= ELEVATION_PROFILE_POINTS; i++) {
			double targetKm = totalKm * i / ELEVATION_PROFILE_POINTS;
			while (idx < points.size() - 2 && points.get(idx + 1).distanceKm() < targetKm) idx++;
			TrackPoint p0 = points.get(idx);
			TrackPoint p1 = idx + 1 < points.size() ? points.get(idx + 1) : p0;
			double e0 = smoothed[idx] != null ? smoothed[idx] : 0.0;
			double e1 = idx + 1 < smoothed.length && smoothed[idx + 1] != null ? smoothed[idx + 1] : e0;
			double t = 0.0;
			if (p1.distanceKm() > p0.distanceKm()) {
				t = Math.max(0.0, Math.min(1.0, (targetKm - p0.distanceKm()) / (p1.distanceKm() - p0.distanceKm())));
			}
			profile.add(List.of(round3(targetKm), round1(e0 + (e1 - e0) * t)));
		}
		return profile;
	}

	static boolean isLoop(List<TrackPoint> points) {
		TrackPoint start = points.get(0);
		TrackPoint end = points.get(points.size() - 1);
		return haversineKm(start.lat(), start.lng(), end.lat(), end.lng()) * 1000.0 <= LOOP_THRESHOLD_M;
	}

	/** Every derived Course field. Sections and climbs aren't among them - they're calculated on
	 * read from elevationSeries with the athlete's current settings (see CourseSections). */
	public static ProcessedTrack processTrackPoints(List<TrackPoint> points) {
		Double[] smoothed = smoothedElevations(points);
		List<Double> rawDistances = new ArrayList<>(points.size());
		List<Double> rawElevations = new ArrayList<>(points.size());
		for (TrackPoint p : points) {
			rawDistances.add(p.distanceKm());
			rawElevations.add(p.elevation());
		}
		CourseSections.ElevationSeries series = CourseSections.buildElevationSeries(rawDistances, rawElevations);
		int[] gainLoss = gainLoss(smoothed);
		List<Double> elevations = new ArrayList<>();
		for (Double v : smoothed) if (v != null) elevations.add(v);

		return new ProcessedTrack(
				points.get(0).lat(), points.get(0).lng(), round3(points.get(points.size() - 1).distanceKm()),
				gainLoss[0], gainLoss[1],
				elevations.isEmpty() ? null : (int) Math.round(elevations.stream().mapToDouble(Double::doubleValue).max().orElse(0)),
				elevations.isEmpty() ? null : (int) Math.round(elevations.stream().mapToDouble(Double::doubleValue).min().orElse(0)),
				isLoop(points), simplifyRoute(points), elevationProfile(points, smoothed), series.elevationQuality(),
				series.series());
	}

	private static double haversineKm(double lat1, double lng1, double lat2, double lng2) {
		double dLat = Math.toRadians(lat2 - lat1);
		double dLng = Math.toRadians(lng2 - lng1);
		double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
				+ Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
		double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
		return EARTH_RADIUS_KM * c;
	}

	private static double round1(double v) {
		return Math.round(v * 10) / 10.0;
	}

	private static double round2(double v) {
		return Math.round(v * 100) / 100.0;
	}

	private static double round3(double v) {
		return Math.round(v * 1000) / 1000.0;
	}

	private static double round6(double v) {
		return Math.round(v * 1_000_000) / 1_000_000.0;
	}
}
