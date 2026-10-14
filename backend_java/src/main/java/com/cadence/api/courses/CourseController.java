package com.cadence.api.courses;

import com.cadence.api.common.error.UnprocessableEntityException;
import com.cadence.api.common.error.ValidationException;
import com.cadence.api.common.paging.DataListResponse;
import com.cadence.api.courses.dto.CourseResponse;
import com.cadence.api.courses.dto.CourseSummaryResponse;
import com.cadence.api.courses.dto.CourseUpdateRequest;
import com.cadence.api.security.AccessGuard;
import com.cadence.api.users.User;
import com.cadence.api.users.UserService;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class CourseController {

	private final CourseService courseService;
	private final UserService userService;
	private final AccessGuard accessGuard;

	public CourseController(CourseService courseService, UserService userService, AccessGuard accessGuard) {
		this.courseService = courseService;
		this.userService = userService;
		this.accessGuard = accessGuard;
	}

	@GetMapping("/v1/courses")
	public DataListResponse<CourseSummaryResponse> listCourses(@RequestParam(required = false) String q) {
		String athleteId = accessGuard.effectiveAthleteId();
		accessGuard.requireRead(athleteId);
		return new DataListResponse<>(courseService.list(athleteId, q).stream().map(courseService::toSummaryResponse).toList());
	}

	@PostMapping(value = "/v1/courses", consumes = "multipart/form-data")
	@ResponseStatus(HttpStatus.CREATED)
	public CourseResponse uploadCourse(
			@RequestPart("gpx") MultipartFile gpx,
			@RequestParam("name") String name,
			@RequestParam(name = "location_name", required = false) String locationName) throws IOException {
		String athleteId = accessGuard.effectiveAthleteId();
		accessGuard.requireWrite(athleteId);
		if (name == null || name.isBlank()) {
			throw new ValidationException("This field is required.", "name");
		}
		User athlete = userService.getById(athleteId);
		Course course = courseService.uploadGpx(athlete, gpx.getBytes(), gpx.getOriginalFilename(), name, locationName);
		return courseService.toResponse(course);
	}

	@PostMapping("/v1/courses/from-activity")
	@ResponseStatus(HttpStatus.CREATED)
	public CourseResponse createCourseFromActivity(@RequestBody FromActivityRequest request) {
		String athleteId = accessGuard.effectiveAthleteId();
		accessGuard.requireWrite(athleteId);
		if (request.activityId() == null || request.activityId().isBlank()) {
			throw new ValidationException("This field is required.", "activity_id");
		}
		if (request.name() == null || request.name().isBlank()) {
			throw new ValidationException("This field is required.", "name");
		}
		User athlete = userService.getById(athleteId);
		Course course = courseService.createFromActivity(athlete, request.activityId(), request.name());
		return courseService.toResponse(course);
	}

	public record FromActivityRequest(String activityId, String name) {}

	/** Query parameters named like course_section_settings' fields override the owner's settings for
	 * this response only - a live preview that saves nothing. */
	@GetMapping("/v1/courses/{id}")
	public CourseResponse getCourse(@PathVariable String id, @RequestParam Map<String, String> query) {
		Course course = courseService.getCourse(id);
		accessGuard.requireRead(course.getAthlete().getId());
		CourseSections.Settings settings = courseService.settingsWithOverrides(course, query);
		course = courseService.ensureElevationSeries(course);
		return courseService.toResponse(course, settings);
	}

	/** Rebuilds the stored elevation series from the course's GPX file or source activity - only
	 * needed after the source data or the resampling code changes, since every section setting
	 * applies at read time. */
	@PostMapping("/v1/courses/{id}/recompute-elevation")
	public CourseResponse recomputeCourseElevation(@PathVariable String id) {
		Course course = courseService.getCourse(id);
		accessGuard.requireWrite(course.getAthlete().getId());
		if (!courseService.rebuildElevationSeries(course)) {
			throw new UnprocessableEntityException(
					"This course's GPX file and source activity are both gone, so its elevation can't be rebuilt.");
		}
		return courseService.toResponse(course);
	}

	@PostMapping("/v1/athletes/{id}/recompute-elevation")
	public CourseService.RecomputeCounts recomputeAthleteCourseElevation(@PathVariable String id) {
		accessGuard.requireWrite(id);
		userService.getById(id);
		return courseService.recomputeElevationForAthlete(id);
	}

	@PatchMapping("/v1/courses/{id}")
	public CourseResponse updateCourse(@PathVariable String id, @RequestBody CourseUpdateRequest request) {
		Course course = courseService.getCourse(id);
		accessGuard.requireWrite(course.getAthlete().getId());
		return courseService.toResponse(courseService.update(course, request));
	}

	@DeleteMapping("/v1/courses/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteCourse(@PathVariable String id) {
		Course course = courseService.getCourse(id);
		accessGuard.requireWrite(course.getAthlete().getId());
		courseService.delete(id);
	}

	@GetMapping("/v1/courses/{id}/gpx")
	public ResponseEntity<byte[]> downloadCourseGpx(@PathVariable String id) {
		Course course = courseService.getCourse(id);
		accessGuard.requireRead(course.getAthlete().getId());
		byte[] content = courseService.gpxFileContent(course);
		String filename = (course.getGpxFilename() != null && !course.getGpxFilename().isBlank())
				? course.getGpxFilename()
				: course.getName() + ".gpx";
		return ResponseEntity.ok()
				.contentType(MediaType.parseMediaType("application/gpx+xml"))
				.header(HttpHeaders.CONTENT_DISPOSITION,
						"attachment; filename=\"" + URLEncoder.encode(filename, StandardCharsets.UTF_8) + "\"")
				.body(content);
	}
}
