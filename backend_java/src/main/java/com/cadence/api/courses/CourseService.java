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
import com.cadence.api.users.UserRepository;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
	private final UserRepository userRepository;

	public CourseService(CourseRepository courseRepository, ActivityRepository activityRepository,
			RecordRepository recordRepository, RaceRepository raceRepository, CourseGeocodingService geocodingService,
			CadenceProperties properties, UserRepository userRepository) {
		this.courseRepository = courseRepository;
		this.activityRepository = activityRepository;
		this.recordRepository = recordRepository;
		this.raceRepository = raceRepository;
		this.geocodingService = geocodingService;
		this.properties = properties;
		this.userRepository = userRepository;
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
		course.setElevationQuality(track.elevationQuality());
		course.setElevationSeries(track.elevationSeries());
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
		// An export from before sections existed has neither - the course then builds its series
		// from the imported GPX or activity on first read (ensureElevationSeries).
		course.setElevationQuality(entry.elevationQuality() != null ? entry.elevationQuality() : "");
		course.setElevationSeries(entry.elevationSeries() != null ? entry.elevationSeries() : List.of());
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
				gpxBase64, course.getRoutePolyline(), course.getElevationProfile(),
				// Calculated with the athlete's settings at export time - kept for readers of older
				// exports; an import rebuilds climbs from elevationSeries instead.
				CourseSections.climbsFromSections(sectionsFor(course, null)), course.getCreatedAt(),
				course.getElevationQuality(), course.getElevationSeries());
	}

	public CourseSummaryResponse toSummaryResponse(Course course) {
		return new CourseSummaryResponse(course.getId(), course.getAthlete().getId(), course.getName(),
				course.getLocationName(), course.getDistanceKm(), course.getSource().wireValue(), course.getCreatedAt(),
				raceRepository.countByCourseId(course.getId()));
	}

	@Transactional(readOnly = true)
	public CourseResponse toResponse(Course course) {
		return toResponse(course, null);
	}

	/** settings: a preview's overrides (see settingsWithOverrides), or null for the owning
	 * athlete's own settings. Sections and climbs are calculated here, never stored. */
	@Transactional(readOnly = true)
	public CourseResponse toResponse(Course course, CourseSections.Settings settings) {
		List<CourseSections.Section> sections = sectionsFor(course, settings);
		List<Race> races = raceRepository.findByCourseIdOrderByDateDesc(course.getId());
		List<CourseResponse.RaceSummary> raceSummaries = races.stream()
				.map(r -> new CourseResponse.RaceSummary(r.getId(), r.getName(), r.getDate(), r.getPriority().wireValue(), r.getResultTime()))
				.toList();
		return new CourseResponse(course.getId(), course.getAthlete().getId(), course.getName(), course.getLocationName(),
				course.getStartLat(), course.getStartLng(), course.getDistanceKm(), course.getElevationGainM(),
				course.getElevationLossM(), course.getElevationMaxM(), course.getElevationMinM(), course.isLoop(),
				course.getSource().wireValue(), course.getSourceActivity() != null ? course.getSourceActivity().getId() : null,
				course.getGpxFilename(), course.getRoutePolyline(), course.getElevationProfile(),
				CourseSections.climbsFromSections(sections), course.getCreatedAt(), races.size(), raceSummaries,
				course.getElevationQuality(), sections);
	}

	/** Sections calculated now from the stored series, with the given settings or else the owning
	 * athlete's. The owner is re-read by id: the course is usually detached by now, so walking its
	 * lazy athlete association would throw (see backend_java/CLAUDE.md). */
	@Transactional(readOnly = true)
	public List<CourseSections.Section> sectionsFor(Course course, CourseSections.Settings settings) {
		if (!CourseSections.ELEVATION_QUALITY_OK.equals(course.getElevationQuality())) return List.of();
		CourseSections.Settings effective = settings != null ? settings : ownerSettings(course);
		return CourseSections.sectionsFromSeries(course.getElevationSeries(), effective);
	}

	private CourseSections.Settings ownerSettings(Course course) {
		return settingsFor(course.getAthlete().getId());
	}

	/** An athlete's saved section settings - shared by course and activity sections. */
	public CourseSections.Settings settingsFor(String athleteId) {
		return userRepository.findById(athleteId)
				.map(User::getCourseSectionSettings)
				.orElse(CourseSections.Settings.DEFAULTS);
	}

	/** The owner's settings with any overrides from the query string (the same snake_case names as
	 * course_section_settings, e.g. ?algorithm=bottom_up&fit_tolerance_m=3) - a live preview that
	 * saves nothing. Null when there are none. */
	@Transactional(readOnly = true)
	public CourseSections.Settings settingsWithOverrides(Course course, Map<String, String> query) {
		return settingsWithOverrides(course.getAthlete().getId(), query);
	}

	/** The athlete's section settings with a preview's query-string overrides, or null when the
	 * query has none. */
	public CourseSections.Settings settingsWithOverrides(String athleteId, Map<String, String> query) {
		String algorithm = query.get("algorithm");
		Double[] values = new Double[8];
		String[] names = {"flat_max_pct", "steep_min_pct", "fit_tolerance_m", "min_section_m", "keep_min_rise_m",
				"spike_window_m", "smoothing_window_m", "resample_step_m"};
		boolean any = algorithm != null;
		for (int i = 0; i < names.length; i++) {
			String raw = query.get(names[i]);
			if (raw == null) continue;
			any = true;
			try {
				values[i] = Double.parseDouble(raw);
			}
			catch (NumberFormatException e) {
				throw new ValidationException("Must be a number.", names[i]);
			}
		}
		if (!any) return null;
		CourseSections.Settings base = settingsFor(athleteId);
		CourseSections.Settings merged = new CourseSections.Settings(
				algorithm != null ? algorithm : base.algorithm(),
				values[0] != null ? values[0] : base.flatMaxPct(),
				values[1] != null ? values[1] : base.steepMinPct(),
				values[2] != null ? values[2] : base.fitToleranceM(),
				values[3] != null ? values[3] : base.minSectionM(),
				values[4] != null ? values[4] : base.keepMinRiseM(),
				values[5] != null ? values[5] : base.spikeWindowM(),
				values[6] != null ? values[6] : base.smoothingWindowM(),
				values[7] != null ? values[7] : base.resampleStepM());
		String error = merged.validationError();
		if (error != null) throw new ValidationException(error, "course_section_settings");
		return merged;
	}

	/** The course's original track - its uploaded GPX if the file still exists, else its source
	 * activity's GPS track - or null when neither is available any more. */
	private List<CourseProcessing.TrackPoint> sourceTrack(Course course) {
		byte[] gpx = originalGpxFileBytes(course);
		if (gpx != null) return CourseProcessing.parseGpxTrack(gpx);
		if (course.getSourceActivity() != null) {
			return CourseProcessing.trackPointsFromActivity(
					recordRepository.findByActivityIdOrderByT(course.getSourceActivity().getId()));
		}
		return null;
	}

	/** Rebuilds elevationSeries/elevationQuality from the course's source data and saves them.
	 * False (nothing changed) when the source GPX file and activity are both gone, or the source
	 * no longer parses. */
	@Transactional
	public boolean rebuildElevationSeries(Course course) {
		List<CourseProcessing.TrackPoint> points;
		try {
			points = sourceTrack(course);
		}
		catch (CourseProcessing.GpxProcessingException e) {
			return false;
		}
		if (points == null) return false;
		List<Double> distances = new ArrayList<>(points.size());
		List<Double> elevations = new ArrayList<>(points.size());
		for (CourseProcessing.TrackPoint p : points) {
			distances.add(p.distanceKm());
			elevations.add(p.elevation());
		}
		CourseSections.ElevationSeries series = CourseSections.buildElevationSeries(distances, elevations);
		course.setElevationQuality(series.elevationQuality());
		course.setElevationSeries(series.series());
		courseRepository.save(course);
		return true;
	}

	/** Fills in the series on first read for a course created before sections existed
	 * (elevationQuality still blank). One with no source data left is marked missing, so this isn't
	 * retried on every read - the recompute endpoints can still try again later. */
	@Transactional
	public Course ensureElevationSeries(Course course) {
		if (course.getElevationQuality() != null && !course.getElevationQuality().isBlank()) return course;
		if (!rebuildElevationSeries(course)) {
			course.setElevationQuality(CourseSections.ELEVATION_QUALITY_MISSING);
			course.setElevationSeries(List.of());
			courseRepository.save(course);
		}
		return course;
	}

	public record RecomputeCounts(int updated, int skipped) {}

	/** rebuildElevationSeries for every one of an athlete's courses, one at a time. Synchronous: an
	 * athlete has tens of courses at most and each takes milliseconds. */
	@Transactional
	public RecomputeCounts recomputeElevationForAthlete(String athleteId) {
		int updated = 0;
		int skipped = 0;
		for (Course course : courseRepository.findByAthleteId(athleteId)) {
			if (rebuildElevationSeries(course)) {
				updated++;
			}
			else {
				skipped++;
			}
		}
		return new RecomputeCounts(updated, skipped);
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
