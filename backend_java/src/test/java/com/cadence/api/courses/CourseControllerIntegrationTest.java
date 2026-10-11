package com.cadence.api.courses;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cadence.api.common.error.ForbiddenException;
import com.cadence.api.common.error.UnprocessableEntityException;
import com.cadence.api.common.error.ValidationException;
import com.cadence.api.courses.dto.CourseResponse;
import com.cadence.api.courses.dto.CourseUpdateRequest;
import com.cadence.api.security.AuthContext;
import com.cadence.api.security.AuthContextHolder;
import com.cadence.api.support.IntegrationTest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;

/** Mirrors the Python backend's courses/tests/test_views.py - GPX upload, permissions, and
 * GPX download/regeneration. Uses the same shared GPX fixtures as CourseProcessingTest. */
class CourseControllerIntegrationTest extends IntegrationTest {

	@Autowired
	private CourseController courseController;
	@Autowired
	private CourseRepository courseRepository;
	@Autowired
	private UserRepository userRepository;

	@AfterEach
	void clearAuthContext() {
		AuthContextHolder.clear();
	}

	private User newUser(String email) {
		User user = new User();
		user.setEmail(email);
		user.setName("User");
		user.setPassword("irrelevant-for-this-test");
		return userRepository.save(user);
	}

	private void authAsSelf(String id) {
		AuthContextHolder.set(AuthContext.self(id, Set.of("activities:read", "activities:write"), AuthContext.CredentialKind.OAUTH2));
	}

	private void authAsDelegated(String sub, String athleteId, Set<String> scopes) {
		AuthContextHolder.set(new AuthContext(sub, athleteId, scopes, AuthContext.CredentialKind.JWT));
	}

	private static byte[] fixture(String name) throws IOException {
		return Files.readAllBytes(Path.of("src/test/resources/gpx-fixtures", name));
	}

	@Test
	void uploadGpxCreatesCourse() throws IOException {
		User athlete = newUser("course-upload@example.cc");
		authAsSelf(athlete.getId());
		MockMultipartFile gpx = new MockMultipartFile("gpx", "hill_loop.gpx", "application/gpx+xml", fixture("hill_loop.gpx"));

		CourseResponse response = courseController.uploadCourse(gpx, "Hill Loop", null);

		assertThat(response.name()).isEqualTo("Hill Loop");
		assertThat(response.isLoop()).isTrue();
		assertThat(response.distanceKm()).isGreaterThan(0);
		assertThat(courseRepository.findByAthleteId(athlete.getId())).hasSize(1);
	}

	@Test
	void uploadRejectsTooSmallGpx() throws IOException {
		User athlete = newUser("course-upload-too-small@example.cc");
		authAsSelf(athlete.getId());
		MockMultipartFile gpx = new MockMultipartFile("gpx", "too_small.gpx", "application/gpx+xml", fixture("too_small.gpx"));

		assertThatThrownBy(() -> courseController.uploadCourse(gpx, "Too Small", null))
				.isInstanceOf(UnprocessableEntityException.class);
	}

	@Test
	void uploadRequiresName() throws IOException {
		User athlete = newUser("course-upload-no-name@example.cc");
		authAsSelf(athlete.getId());
		MockMultipartFile gpx = new MockMultipartFile("gpx", "hill_loop.gpx", "application/gpx+xml", fixture("hill_loop.gpx"));

		assertThatThrownBy(() -> courseController.uploadCourse(gpx, "", null)).isInstanceOf(ValidationException.class);
	}

	@Test
	void outsiderCannotReadAnothersCourse() {
		User athlete = newUser("course-detail-athlete@example.cc");
		User outsider = newUser("course-detail-outsider@example.cc");
		Course course = new Course();
		course.setAthlete(athlete);
		course.setName("Mine");
		course.setSource(CourseSource.GPX);
		course = courseRepository.save(course);
		final String courseId = course.getId();

		// A delegated token naming this athlete, but with no real UserRelationship grant
		// behind it - AccessGuard.requireRead must still deny it.
		authAsDelegated(outsider.getId(), athlete.getId(), Set.of("activities:read"));

		assertThatThrownBy(() -> courseController.getCourse(courseId)).isInstanceOf(ForbiddenException.class);
	}

	@Test
	void coachWithoutGrantCannotWrite() {
		User athlete = newUser("course-write-athlete@example.cc");
		User coach = newUser("course-write-coach@example.cc");
		Course course = new Course();
		course.setAthlete(athlete);
		course.setName("Original");
		course.setSource(CourseSource.GPX);
		course = courseRepository.save(course);
		final String courseId = course.getId();

		authAsDelegated(coach.getId(), athlete.getId(), Set.of("activities:read", "activities:write"));

		assertThatThrownBy(() -> courseController.updateCourse(courseId, new CourseUpdateRequest("Hijacked", null)))
				.isInstanceOf(ForbiddenException.class);
	}

	@Test
	void athleteCanRenameOwnCourse() {
		User athlete = newUser("course-rename@example.cc");
		Course course = new Course();
		course.setAthlete(athlete);
		course.setName("Original");
		course.setSource(CourseSource.GPX);
		course = courseRepository.save(course);
		authAsSelf(athlete.getId());

		CourseResponse response = courseController.updateCourse(course.getId(), new CourseUpdateRequest("Renamed", null));

		assertThat(response.name()).isEqualTo("Renamed");
	}

	@Test
	void deleteRemovesCourse() {
		User athlete = newUser("course-delete@example.cc");
		Course course = new Course();
		course.setAthlete(athlete);
		course.setName("Doomed");
		course.setSource(CourseSource.GPX);
		course = courseRepository.save(course);
		final String courseId = course.getId();
		authAsSelf(athlete.getId());

		courseController.deleteCourse(courseId);

		assertThat(courseRepository.findById(courseId)).isEmpty();
	}

	@Test
	void gpxDownloadRegeneratesWhenNoOriginalFile() {
		User athlete = newUser("course-gpx-download@example.cc");
		Course course = new Course();
		course.setAthlete(athlete);
		course.setName("From Activity Course");
		course.setSource(CourseSource.ACTIVITY);
		course.setRoutePolyline(java.util.List.of(java.util.List.of(51.5, -0.1), java.util.List.of(51.6, -0.2)));
		course = courseRepository.save(course);
		authAsSelf(athlete.getId());

		var response = courseController.downloadCourseGpx(course.getId());

		assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
		assertThat(new String(response.getBody())).contains("<gpx");
	}
}
