package com.cadence.api.races;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cadence.api.activities.Activity;
import com.cadence.api.activities.ActivityRepository;
import com.cadence.api.activities.Lap;
import com.cadence.api.activities.LapRepository;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.courses.Course;
import com.cadence.api.courses.CourseRepository;
import com.cadence.api.courses.CourseSource;
import com.cadence.api.races.dto.RaceUpdateRequest;
import com.cadence.api.support.IntegrationTest;
import com.cadence.api.tokens.AccessTokenService;
import com.cadence.api.tokens.dto.CreateAccessTokenRequest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** is_public opt-in plus the unauthenticated /v1/public/races/{id} read endpoints - through
 * MockMvc, so SecurityConfig's permitAll entry is exercised too, not just the handlers. Mirrors
 * the Python backend's races/tests/test_public_race.py. */
@AutoConfigureMockMvc
class PublicRaceIntegrationTest extends IntegrationTest {

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private ActivityRepository activityRepository;
	@Autowired
	private LapRepository lapRepository;
	@Autowired
	private CourseRepository courseRepository;
	@Autowired
	private RaceRepository raceRepository;
	@Autowired
	private RaceService raceService;
	@Autowired
	private AccessTokenService accessTokenService;
	@Autowired
	private JsonMapper jsonMapper;

	private Race race(String email) {
		User athlete = new User();
		athlete.setEmail(email);
		athlete.setName("Pat Racer");
		athlete.setPassword("irrelevant-for-this-test");
		userRepository.save(athlete);

		Activity activity = new Activity();
		activity.setAthlete(athlete);
		activity.setSport(Sport.RUN);
		activity.setName("Publicrace run");
		activity.setStartDate(Instant.parse("2026-03-01T08:00:00Z"));
		activity.setAvgHr(150);
		activity.setAvgPower(250);
		activityRepository.save(activity);
		for (int i = 1; i <= 2; i++) {
			Lap lap = new Lap();
			lap.setActivity(activity);
			lap.setIndex(i);
			lap.setDuration(i == 1 ? 300 : 290);
			lap.setDistanceKm(1.0);
			lapRepository.save(lap);
		}

		Course course = new Course();
		course.setAthlete(athlete);
		course.setName("Publicrace Parkland 2K");
		course.setSource(CourseSource.GPX);
		course.setGpxFilename("secret-home-loop.gpx");
		course.setDistanceKm(2.0);
		course.setRoutePolyline(List.of(List.of(51.5, -0.1), List.of(51.51, -0.11)));
		course.setElevationQuality("missing");
		course.setElevationSeries(List.of());
		courseRepository.save(course);

		Race race = new Race();
		race.setAthlete(athlete);
		race.setName("Publicrace Club 2K");
		race.setDate(LocalDate.of(2026, 3, 1));
		race.setSport(Sport.RUN);
		race.setDistanceKm(2.0);
		race.setGoalTime(600);
		race.setResultTime(590);
		race.setTargetPower(260);
		race.setNotes("private notes");
		race.setActivity(activity);
		race.setCourse(course);
		return raceRepository.save(race);
	}

	private void setPublic(Race race, boolean value) {
		raceService.updateRace(race, new RaceUpdateRequest(
				null, null, null, null, null, null, null, null, null, null, null, null, null, null, value));
	}

	private String tokenFor(User athlete) {
		AccessTokenService.CreatedToken token = accessTokenService.create(
				athlete, new CreateAccessTokenRequest("Test token", List.of("activities:write"), null, null), Set.of());
		return token.secret();
	}

	@Test
	void privateByDefaultAndIndistinguishableFromMissing() throws Exception {
		Race race = race("publicrace-private@example.cc");

		assertThat(race.isPublic()).isFalse();
		for (String suffix : List.of("", "/splits", "/weather")) {
			mockMvc.perform(get("/v1/public/races/" + race.getId() + suffix)).andExpect(status().isNotFound());
		}
		mockMvc.perform(get("/v1/public/races/race_doesnotexist")).andExpect(status().isNotFound());
	}

	@Test
	void publishedRaceIsReadableAnonymouslyWithAnAllowlistedPayload() throws Exception {
		Race race = race("publicrace-published@example.cc");
		setPublic(race, true);

		mockMvc.perform(get("/v1/public/races/" + race.getId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.athlete_name").value("Pat Racer"))
				.andExpect(jsonPath("$.name").value("Publicrace Club 2K"))
				.andExpect(jsonPath("$.goal_time").value("0:10:00"))
				.andExpect(jsonPath("$.result_time").value("0:09:50"))
				.andExpect(jsonPath("$.activity.avg_hr").value(150))
				.andExpect(jsonPath("$.activity.avg_power").value(250))
				.andExpect(jsonPath("$.course.name").value("Publicrace Parkland 2K"))
				.andExpect(jsonPath("$.course.route_polyline[1][0]").value(51.51))
				.andExpect(jsonPath("$.athlete_id").doesNotExist())
				.andExpect(jsonPath("$.notes").doesNotExist())
				.andExpect(jsonPath("$.priority").doesNotExist())
				.andExpect(jsonPath("$.target_power").doesNotExist())
				.andExpect(jsonPath("$.activity_id").doesNotExist())
				.andExpect(jsonPath("$.course.athlete_id").doesNotExist())
				.andExpect(jsonPath("$.course.gpx_filename").doesNotExist())
				.andExpect(jsonPath("$.course.races").doesNotExist());
	}

	@Test
	void publicLapSplits() throws Exception {
		Race race = race("publicrace-laps@example.cc");
		setPublic(race, true);

		mockMvc.perform(get("/v1/public/races/" + race.getId() + "/splits").param("mode", "laps"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.has_laps").value(true))
				.andExpect(jsonPath("$.splits[0].time_s").value(300))
				.andExpect(jsonPath("$.splits[1].time_s").value(290));
	}

	@Test
	void publicSplitsRejectUnknownModeAndCustomSplits() throws Exception {
		Race race = race("publicrace-modes@example.cc");
		setPublic(race, true);

		mockMvc.perform(get("/v1/public/races/" + race.getId() + "/splits").param("mode", "bogus"))
				.andExpect(status().isBadRequest());
		mockMvc.perform(post("/v1/public/races/" + race.getId() + "/splits")
						.contentType(MediaType.APPLICATION_JSON).content("{\"steps\":[]}"))
				.andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(401, 405));
	}

	@Test
	void publicWeatherForAPastRaceIsUnavailableNotAnError() throws Exception {
		Race race = race("publicrace-weather@example.cc");
		setPublic(race, true);

		mockMvc.perform(get("/v1/public/races/" + race.getId() + "/weather"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.available").value(false));
	}

	@Test
	void unpublishingRevokesAccess() throws Exception {
		Race race = race("publicrace-revoked@example.cc");
		setPublic(race, true);
		setPublic(race, false);

		mockMvc.perform(get("/v1/public/races/" + race.getId())).andExpect(status().isNotFound());
	}

	@Test
	void publishingDoesNotOpenUpTheAuthenticatedEndpoints() throws Exception {
		Race race = race("publicrace-authed@example.cc");
		setPublic(race, true);

		mockMvc.perform(get("/v1/races/" + race.getId())).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/v1/races/" + race.getId() + "/block")).andExpect(status().isUnauthorized());
	}

	@Test
	void rejectsANonHttpUrlOnARaceThatCouldBeMadePublic() throws Exception {
		Race race = race("publicrace-url-validation@example.cc");
		String bearer = tokenFor(race.getAthlete());

		mockMvc.perform(patch("/v1/races/" + race.getId())
						.header("Authorization", "Bearer " + bearer)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"url\": \"javascript:alert(1)\"}"))
				.andExpect(status().isBadRequest());

		// A blank string (clearing the field) and a real http(s) URL both still work.
		mockMvc.perform(patch("/v1/races/" + race.getId())
						.header("Authorization", "Bearer " + bearer)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"url\": \"https://example.com/results\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.url").value("https://example.com/results"));
		mockMvc.perform(patch("/v1/races/" + race.getId())
						.header("Authorization", "Bearer " + bearer)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"url\": \"\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.url").doesNotExist());
	}

	@Test
	void isPublicUsesItsSnakeCaseNameOnTheWire() {
		RaceUpdateRequest request = jsonMapper.readValue("{\"is_public\": true}", RaceUpdateRequest.class);
		assertThat(request.isPublic()).isTrue();

		Race race = race("publicrace-wire@example.cc");
		setPublic(race, true);
		JsonNode json = jsonMapper.valueToTree(raceService.toResponse(raceRepository.findById(race.getId()).orElseThrow()));
		assertThat(json.get("is_public").asBoolean()).isTrue();
	}
}
