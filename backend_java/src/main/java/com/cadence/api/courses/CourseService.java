package com.cadence.api.courses;

import com.cadence.api.activities.Activity;
import com.cadence.api.activities.ActivityRepository;
import com.cadence.api.activities.RecordRepository;
import com.cadence.api.common.config.CadenceProperties;
import com.cadence.api.common.error.ForbiddenException;
import com.cadence.api.common.error.NotFoundException;
import com.cadence.api.common.error.UnprocessableEntityException;
import com.cadence.api.common.error.ValidationException;
import com.cadence.api.courses.dto.CourseResponse;
import com.cadence.api.courses.dto.CourseSummaryResponse;
import com.cadence.api.courses.dto.CourseUpdateRequest;
import com.cadence.api.export.dto.CourseExportEntry;
import com.cadence.api.races.Race;
import com.cadence.api.races.RaceRepository;
import com.cadence.api.users.User;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Course CRUD, GPX upload and from-activity creation - mirrors the Python backend's
 * courses/views.py. */
@Service
public class CourseService {

	private final CourseRepository courseRepository;
	private final ActivityRepository activityRepository;
	private final RecordRepository recordRepository;
	private final RaceRepository raceRepository;
	private final CourseGeocodingService geocodingService;
	private final CadenceProperties properties;

	public CourseService(CourseRepository courseRepository, ActivityRepository activityRepository,
			RecordRepository recordRepository, RaceRepository raceRepository, CourseGeocodingService geocodingService,
			CadenceProperties properties) {
		this.courseRepository = courseRepository;
		this.activityRepository = activityRepository;
		this.recordRepository = recordRepository;
		this.raceRepository = raceRepository;
		this.geocodingService = geocodingService;
		this.properties = properties;
	}

	@Transactional(readOnly = true)
	public List<Course> list(String athleteId, String q) {
		return (q == null || q.isBlank()) ? courseRepository.findByAthleteId(athleteId) : courseRepository.searchByAthleteId(athleteId, q);
	}

	@Transactional(readOnly = true)
	public Course getCourse(String id) {
		return courseRepository.findById(id).orElseThrow(() -> new NotFoundException("No such course."));
	}

	@Transactional
	public Course uploadGpx(User athlete, byte[] gpxBytes, String filename, String name, String locationNameOverride) {
		CourseProcessing.ProcessedTrack track;
		try {
			track = CourseProcessing.processTrackPoints(CourseProcessing.parseGpxTrack(gpxBytes));
		}
		catch (CourseProcessing.GpxProcessingException e) {
			throw new UnprocessableEntityException(e.getMessage());
		}

		String locationName = (locationNameOverride != null && !locationNameOverride.isBlank())
				? locationNameOverride
				: geocodingService.reverseGeocode(track.startLat(), track.startLng());

		Course course = fromProcessedTrack(athlete, name, locationName, CourseSource.GPX, null, track);
		course = courseRepository.save(course);

		course.setGpxFilename(filename);
		course.setGpxFile(storeGpxFile(athlete.getId(), course.getId(), gpxBytes));
		return courseRepository.save(course);
	}

	@Transactional
	public Course createFromActivity(User athlete, String activityId, String name) {
		Activity activity = activityRepository.findById(activityId).orElseThrow(() -> new NotFoundException("No such activity."));
		if (!activity.getAthlete().getId().equals(athlete.getId())) {
			throw new ForbiddenException("That activity does not belong to this athlete.");
		}

		CourseProcessing.ProcessedTrack track;
		try {
			List<com.cadence.api.activities.Record> records = recordRepository.findByActivityIdOrderByT(activityId);
			track = CourseProcessing.processTrackPoints(CourseProcessing.trackPointsFromActivity(records));
		}
		catch (CourseProcessing.GpxProcessingException e) {
			throw new UnprocessableEntityException(e.getMessage());
		}

		String locationName = geocodingService.reverseGeocode(track.startLat(), track.startLng());
		Course course = fromProcessedTrack(athlete, name, locationName, CourseSource.ACTIVITY, activity, track);
		return courseRepository.save(course);
	}

	private Course fromProcessedTrack(
			User athlete, String name, String locationName, CourseSource source, Activity sourceActivity,
			CourseProcessing.ProcessedTrack track) {
		Course course = new Course();
		course.setAthlete(athlete);
		course.setName(name);
		course.setLocationName(locationName);
		course.setSource(source);
		course.setSourceActivity(sourceActivity);
		course.setStartLat(track.startLat());
		course.setStartLng(track.startLng());
		course.setDistanceKm(track.distanceKm());
		course.setElevationGainM(track.elevationGainM());
		course.setElevationLossM(track.elevationLossM());
		course.setElevationMaxM(track.elevationMaxM());
		course.setElevationMinM(track.elevationMinM());
		course.setLoop(track.isLoop());
		course.setRoutePolyline(track.routePolyline());
		course.setElevationProfile(track.elevationProfile());
		course.setClimbs(track.climbs());
		return course;
	}

	@Transactional
	public Course update(Course course, CourseUpdateRequest request) {
		if (request.name() != null) {
			if (request.name().isBlank()) throw new ValidationException("Cannot be blank.", "name");
			course.setName(request.name());
		}
		if (request.locationName() != null) {
			course.setLocationName(request.locationName());
		}
		return courseRepository.save(course);
	}

	@Transactional
	public void delete(String id) {
		courseRepository.deleteById(id);
	}

	@Transactional
	public Course importCourse(User athlete, CourseExportEntry entry, String newSourceActivityId) {
		Course course = new Course();
		course.setAthlete(athlete);
		course.setName(entry.name());
		course.setLocationName(entry.locationName() != null ? entry.locationName() : "");
		course.setStartLat(entry.startLat());
		course.setStartLng(entry.startLng());
		course.setDistanceKm(entry.distanceKm());
		course.setElevationGainM(entry.elevationGainM());
		course.setElevationLossM(entry.elevationLossM());
		course.setElevationMaxM(entry.elevationMaxM());
		course.setElevationMinM(entry.elevationMinM());
		course.setLoop(entry.isLoop());
		course.setSource(entry.source() != null && !entry.source().isBlank() ? CourseSource.fromWireValue(entry.source()) : CourseSource.GPX);
		course.setSourceActivity(newSourceActivityId != null ? activityRepository.findById(newSourceActivityId).orElse(null) : null);
		course.setGpxFilename(entry.gpxFilename() != null ? entry.gpxFilename() : "");
		course.setRoutePolyline(entry.routePolyline() != null ? entry.routePolyline() : List.of());
		course.setElevationProfile(entry.elevationProfile() != null ? entry.elevationProfile() : List.of());
		course.setClimbs(entry.climbs() != null ? entry.climbs() : List.of());
		course = courseRepository.save(course);

		if (entry.gpxBase64() != null) {
			byte[] raw = java.util.Base64.getDecoder().decode(entry.gpxBase64());
			course.setGpxFile(storeGpxFile(athlete.getId(), course.getId(), raw));
			course = courseRepository.save(course);
		}
		return course;
	}

	public CourseExportEntry toExportEntry(Course course) {
		byte[] original = originalGpxFileBytes(course);
		String gpxBase64 = original != null ? java.util.Base64.getEncoder().encodeToString(original) : null;
		return new CourseExportEntry(course.getId(), course.getName(), course.getLocationName(), course.getStartLat(),
				course.getStartLng(), course.getDistanceKm(), course.getElevationGainM(), course.getElevationLossM(),
				course.getElevationMaxM(), course.getElevationMinM(), course.isLoop(), course.getSource().wireValue(),
				course.getSourceActivity() != null ? course.getSourceActivity().getId() : null, course.getGpxFilename(),
				gpxBase64, course.getRoutePolyline(), course.getElevationProfile(), course.getClimbs(), course.getCreatedAt());
	}

	public CourseSummaryResponse toSummaryResponse(Course course) {
		return new CourseSummaryResponse(course.getId(), course.getAthlete().getId(), course.getName(),
				course.getLocationName(), course.getDistanceKm(), course.getSource().wireValue(), course.getCreatedAt(),
				raceRepository.countByCourseId(course.getId()));
	}

	@Transactional(readOnly = true)
	public CourseResponse toResponse(Course course) {
		List<Race> races = raceRepository.findByCourseIdOrderByDateDesc(course.getId());
		List<CourseResponse.RaceSummary> raceSummaries = races.stream()
				.map(r -> new CourseResponse.RaceSummary(r.getId(), r.getName(), r.getDate(), r.getPriority().wireValue(), r.getResultTime()))
				.toList();
		return new CourseResponse(course.getId(), course.getAthlete().getId(), course.getName(), course.getLocationName(),
				course.getStartLat(), course.getStartLng(), course.getDistanceKm(), course.getElevationGainM(),
				course.getElevationLossM(), course.getElevationMaxM(), course.getElevationMinM(), course.isLoop(),
				course.getSource().wireValue(), course.getSourceActivity() != null ? course.getSourceActivity().getId() : null,
				course.getGpxFilename(), course.getRoutePolyline(), course.getElevationProfile(), course.getClimbs(),
				course.getCreatedAt(), races.size(), raceSummaries);
	}

	/** The original uploaded GPX's bytes, or null if there isn't one (a course created from an
	 * activity, or the file is missing) - distinct from {@link #gpxFileContent}, which falls
	 * back to a regenerated GPX for display/download; export needs to know the difference. */
	public byte[] originalGpxFileBytes(Course course) {
		if (course.getGpxFile() == null || course.getGpxFile().isBlank()) return null;
		Path path = Path.of(properties.uploads().mediaRoot(), course.getGpxFile());
		if (!Files.exists(path)) return null;
		try {
			return Files.readAllBytes(path);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** Reads the original uploaded GPX if one exists, or regenerates a minimal one from the
	 * stored route for a course created from an activity. */
	public byte[] gpxFileContent(Course course) {
		byte[] original = originalGpxFileBytes(course);
		return original != null ? original : generateGpx(course).getBytes(java.nio.charset.StandardCharsets.UTF_8);
	}

	private String generateGpx(Course course) {
		StringBuilder points = new StringBuilder();
		for (List<Double> point : course.getRoutePolyline()) {
			points.append("<trkpt lat=\"").append(point.get(0)).append("\" lon=\"").append(point.get(1)).append("\"></trkpt>");
		}
		return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
				+ "<gpx version=\"1.1\" creator=\"Cadence\">"
				+ "<trk><name>" + escapeXml(course.getName()) + "</name><trkseg>" + points + "</trkseg></trk>"
				+ "</gpx>";
	}

	private String escapeXml(String s) {
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
	}

	private String storeGpxFile(String athleteId, String courseId, byte[] bytes) {
		try {
			Path dir = Path.of(properties.uploads().mediaRoot(), "courses", athleteId);
			Files.createDirectories(dir);
			String relativePath = "courses/" + athleteId + "/" + courseId + ".gpx";
			Files.write(Path.of(properties.uploads().mediaRoot(), relativePath), bytes);
			return relativePath;
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
