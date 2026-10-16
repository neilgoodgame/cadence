package com.cadence.api.races;

import static org.assertj.core.api.Assertions.assertThat;

import com.cadence.api.courses.Course;
import com.cadence.api.courses.CourseRepository;
import com.cadence.api.courses.CourseSource;
import com.cadence.api.races.dto.RaceWeatherResponse;
import com.cadence.api.security.AuthContext;
import com.cadence.api.security.AuthContextHolder;
import com.cadence.api.support.IntegrationTest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import java.time.LocalDate;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Only the deterministic "unavailable" paths - available:true needs an actual Open-Meteo call,
 * which (like CourseGeocodingService's Nominatim call) this codebase doesn't test against a
 * live network dependency. */
class RaceWeatherIntegrationTest extends IntegrationTest {

	@Autowired
	private RaceController raceController;
	@Autowired
	private RaceRepository raceRepository;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private CourseRepository courseRepository;

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

	@Test
	void unavailableWhenRaceHasNoCourse() {
		User athlete = newUser("race-weather-no-course@example.cc");
		Race race = new Race();
		race.setAthlete(athlete);
		race.setName("Goal Marathon");
		race.setDate(LocalDate.now().plusDays(3));
		race = raceRepository.save(race);
		authAsSelf(athlete.getId());

		RaceWeatherResponse response = raceController.getRaceWeather(race.getId());

		assertThat(response.available()).isFalse();
	}

	@Test
	void unavailableWhenCourseHasNoStartPoint() {
		User athlete = newUser("race-weather-no-start-point@example.cc");
		Course course = new Course();
		course.setAthlete(athlete);
		course.setName("Flat GPX, no start point resolved");
		course.setSource(CourseSource.GPX);
		course = courseRepository.save(course);
		Race race = new Race();
		race.setAthlete(athlete);
		race.setName("Goal Marathon");
		race.setDate(LocalDate.now().plusDays(3));
		race.setCourse(course);
		race = raceRepository.save(race);
		authAsSelf(athlete.getId());

		RaceWeatherResponse response = raceController.getRaceWeather(race.getId());

		assertThat(response.available()).isFalse();
	}

	@Test
	void unavailableWhenRaceIsBeyondTheForecastWindow() {
		User athlete = newUser("race-weather-too-far-out@example.cc");
		Course course = new Course();
		course.setAthlete(athlete);
		course.setName("Valencia");
		course.setSource(CourseSource.GPX);
		course.setStartLat(39.47);
		course.setStartLng(-0.38);
		course = courseRepository.save(course);
		Race race = new Race();
		race.setAthlete(athlete);
		race.setName("Goal Marathon");
		race.setDate(LocalDate.now().plusDays(30));
		race.setCourse(course);
		race = raceRepository.save(race);
		authAsSelf(athlete.getId());

		RaceWeatherResponse response = raceController.getRaceWeather(race.getId());

		assertThat(response.available()).isFalse();
	}

	@Test
	void unavailableWhenRaceIsInThePast() {
		User athlete = newUser("race-weather-in-the-past@example.cc");
		Course course = new Course();
		course.setAthlete(athlete);
		course.setName("Valencia Past");
		course.setSource(CourseSource.GPX);
		course.setStartLat(39.47);
		course.setStartLng(-0.38);
		course = courseRepository.save(course);
		Race race = new Race();
		race.setAthlete(athlete);
		race.setName("Goal Marathon");
		race.setDate(LocalDate.now().minusDays(1));
		race.setCourse(course);
		race = raceRepository.save(race);
		authAsSelf(athlete.getId());

		RaceWeatherResponse response = raceController.getRaceWeather(race.getId());

		assertThat(response.available()).isFalse();
	}
}
