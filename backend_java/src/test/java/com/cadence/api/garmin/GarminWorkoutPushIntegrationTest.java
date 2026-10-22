package com.cadence.api.garmin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cadence.api.common.domain.Sport;
import com.cadence.api.support.IntegrationTest;
import com.cadence.api.tokens.AccessTokenService;
import com.cadence.api.tokens.dto.CreateAccessTokenRequest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import com.cadence.api.workouts.Workout;
import com.cadence.api.workouts.WorkoutRepository;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** The workout-push outbox: what the browser queues and what the local bridge reports back. */
@AutoConfigureMockMvc
class GarminWorkoutPushIntegrationTest extends IntegrationTest {

	private static final String KEY = "garmin-wpush-test-key-0001";
	private static final String TCX = "<TrainingCenterDatabase/>";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private AccessTokenService accessTokenService;

	@Autowired
	private GarminConnectionService connectionService;

	@Autowired
	private WorkoutRepository workoutRepository;

	private User athlete(String email, boolean connected) {
		User user = new User();
		user.setEmail(email);
		user.setName("Athlete");
		user.setPassword("irrelevant-for-this-test");
		user = userRepository.save(user);
		if (connected) {
			connectionService.save(user, KEY, null, null, null);
		}
		return user;
	}

	private Workout workout(User owner, String name) {
		Workout workout = new Workout();
		workout.setCreatedBy(owner);
		workout.setName(name);
		workout.setSport(Sport.BIKE);
		return workoutRepository.save(workout);
	}

	private String bearer(User user) {
		return "Bearer " + accessTokenService
				.create(user, new CreateAccessTokenRequest("garmin-wpush-test", List.of("workouts:write"), null, null), Set.of())
				.secret();
	}

	private MvcResult enqueue(User user, String json) throws Exception {
		return mockMvc.perform(post("/v1/garmin/workout-ops").header("Authorization", bearer(user))
				.contentType(MediaType.APPLICATION_JSON).content(json)).andReturn();
	}

	private String upsertBody(String workoutId, String tcx) {
		return "{\"kind\":\"upsert_workout\",\"workout_id\":\"" + workoutId + "\",\"name\":\"Test\",\"tcx\":\"" + tcx + "\"}";
	}

	private String scheduleBody(String kind, String workoutId, String scheduledId, String date) {
		return "{\"kind\":\"" + kind + "\",\"workout_id\":\"" + workoutId + "\",\"scheduled_workout_id\":\""
				+ scheduledId + "\"" + (date == null ? "" : ",\"date\":\"" + date + "\"") + "}";
	}

	private String report(User user, String opId, String json) throws Exception {
		MvcResult r = mockMvc.perform(post("/v1/garmin/workout-ops/" + opId + "/result").header("Authorization", bearer(user))
				.contentType(MediaType.APPLICATION_JSON).content(json)).andReturn();
		return r.getResponse().getStatus() + " " + r.getResponse().getContentAsString();
	}

	@Test
	void kindsAreAcceptedInLowerOrUpperCase() throws Exception {
		User user = athlete("garmin-wpush-case@example.cc", true);
		Workout w = workout(user, "w");

		assertThat(enqueue(user, upsertBody(w.getId(), TCX).replace("upsert_workout", "UPSERT_WORKOUT")).getResponse().getStatus())
				.isEqualTo(202);
		assertThat(enqueue(user, upsertBody(w.getId(), TCX).replace("upsert_workout", "nonsense")).getResponse().getStatus())
				.isEqualTo(400);
	}

	@Test
	void queuingNeedsAGarminConnection() throws Exception {
		User user = athlete("garmin-wpush-noconn@example.cc", false);
		Workout w = workout(user, "w");

		MvcResult r = enqueue(user, upsertBody(w.getId(), TCX));

		assertThat(r.getResponse().getStatus()).isEqualTo(409);
	}

	@Test
	void anUnknownOrSomeoneElsesWorkoutIsNotFound() throws Exception {
		User user = athlete("garmin-wpush-owner@example.cc", true);
		User other = athlete("garmin-wpush-other@example.cc", true);
		Workout theirs = workout(other, "theirs");

		assertThat(enqueue(user, upsertBody(theirs.getId(), TCX)).getResponse().getStatus()).isEqualTo(404);
		assertThat(enqueue(user, upsertBody("wkt_nope", TCX)).getResponse().getStatus()).isEqualTo(404);
	}

	@Test
	void aPushedWorkoutIsMergedWhilePendingAndLinkedOnceTheBridgeReports() throws Exception {
		User user = athlete("garmin-wpush-upsert@example.cc", true);
		Workout w = workout(user, "w");
		String auth = bearer(user);

		MvcResult first = enqueue(user, upsertBody(w.getId(), TCX));
		assertThat(first.getResponse().getStatus()).isEqualTo(202);
		String opId = JsonPath.read(first.getResponse().getContentAsString(), "$.id");
		enqueue(user, upsertBody(w.getId(), "<Edited/>"));

		mockMvc.perform(get("/v1/garmin/workout-ops").header("Authorization", auth))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data", hasSize(1)))
				.andExpect(jsonPath("$.data[0].id").value(opId))
				.andExpect(jsonPath("$.data[0].kind").value("upsert_workout"))
				.andExpect(jsonPath("$.data[0].tcx").value("<Edited/>"))
				.andExpect(jsonPath("$.data[0].garmin_workout_id").value(nullValue()));

		assertThat(report(user, opId, "{\"status\":\"done\",\"garmin_workout_id\":555}")).startsWith("200");

		mockMvc.perform(get("/v1/garmin/workout-ops").header("Authorization", auth))
				.andExpect(jsonPath("$.data", hasSize(0)));
		mockMvc.perform(get("/v1/garmin/workout-links").header("Authorization", auth))
				.andExpect(jsonPath("$.workouts[0].workout_id").value(w.getId()))
				.andExpect(jsonPath("$.workouts[0].garmin_workout_id").value(555))
				.andExpect(jsonPath("$.pending").value(0));
	}

	@Test
	void scheduleRescheduleAndCancelFollowTheGarminIds() throws Exception {
		User user = athlete("garmin-wpush-schedule@example.cc", true);
		Workout w = workout(user, "w");
		String auth = bearer(user);
		String opId = JsonPath.read(enqueue(user, upsertBody(w.getId(), TCX)).getResponse().getContentAsString(), "$.id");
		report(user, opId, "{\"status\":\"done\",\"garmin_workout_id\":555}");

		MvcResult schedule = enqueue(user, scheduleBody("schedule", w.getId(), "sch_1", "2026-11-01"));
		assertThat(schedule.getResponse().getStatus()).isEqualTo(202);
		String scheduleOp = JsonPath.read(schedule.getResponse().getContentAsString(), "$.id");
		assertThat((String) JsonPath.read(schedule.getResponse().getContentAsString(), "$.kind")).isEqualTo("schedule");
		assertThat(((Number) JsonPath.read(schedule.getResponse().getContentAsString(), "$.garmin_workout_id")).longValue()).isEqualTo(555L);
		assertThat(report(user, scheduleOp, "{\"status\":\"done\",\"garmin_schedule_id\":777}")).startsWith("200");

		// Moving it is now a reschedule that knows the Garmin schedule id; a second move merges into it.
		MvcResult move = enqueue(user, scheduleBody("reschedule", w.getId(), "sch_1", "2026-11-02"));
		assertThat((String) JsonPath.read(move.getResponse().getContentAsString(), "$.kind")).isEqualTo("reschedule");
		assertThat(((Number) JsonPath.read(move.getResponse().getContentAsString(), "$.garmin_schedule_id")).longValue()).isEqualTo(777L);
		enqueue(user, scheduleBody("reschedule", w.getId(), "sch_1", "2026-11-03"));
		mockMvc.perform(get("/v1/garmin/workout-ops").header("Authorization", auth))
				.andExpect(jsonPath("$.data", hasSize(1)))
				.andExpect(jsonPath("$.data[0].date").value("2026-11-03"));
		String moveOp = JsonPath.read(move.getResponse().getContentAsString(), "$.id");
		report(user, moveOp, "{\"status\":\"done\",\"garmin_schedule_id\":778,\"garmin_workout_id\":555}");
		mockMvc.perform(get("/v1/garmin/workout-links").header("Authorization", auth))
				.andExpect(jsonPath("$.schedules[0].garmin_schedule_id").value(778))
				.andExpect(jsonPath("$.schedules[0].scheduled_for").value("2026-11-03"));

		MvcResult cancel = enqueue(user, scheduleBody("unschedule", w.getId(), "sch_1", null));
		assertThat(cancel.getResponse().getStatus()).isEqualTo(202);
		assertThat(((Number) JsonPath.read(cancel.getResponse().getContentAsString(), "$.garmin_schedule_id")).longValue()).isEqualTo(778L);
		report(user, JsonPath.read(cancel.getResponse().getContentAsString(), "$.id"), "{\"status\":\"done\"}");
		mockMvc.perform(get("/v1/garmin/workout-links").header("Authorization", auth))
				.andExpect(jsonPath("$.schedules", hasSize(0)));
	}

	@Test
	void cancellingAScheduleThatNeverReachedGarminJustDropsIt() throws Exception {
		User user = athlete("garmin-wpush-drop@example.cc", true);
		Workout w = workout(user, "w");
		String auth = bearer(user);
		enqueue(user, scheduleBody("schedule", w.getId(), "sch_drop", "2026-11-01"));

		MvcResult cancel = enqueue(user, scheduleBody("unschedule", w.getId(), "sch_drop", null));

		assertThat(cancel.getResponse().getStatus()).isEqualTo(204);
		mockMvc.perform(get("/v1/garmin/workout-ops").header("Authorization", auth))
				.andExpect(jsonPath("$.data", hasSize(0)));
	}

	@Test
	void aFailureIsKeptWithItsReasonAndCanBeDiscarded() throws Exception {
		User user = athlete("garmin-wpush-fail@example.cc", true);
		Workout w = workout(user, "w");
		String auth = bearer(user);
		String opId = JsonPath.read(enqueue(user, upsertBody(w.getId(), TCX)).getResponse().getContentAsString(), "$.id");

		assertThat(report(user, opId, "{\"status\":\"failed\",\"error\":\"Garmin said no\"}")).startsWith("200");

		mockMvc.perform(get("/v1/garmin/workout-ops?status=failed").header("Authorization", auth))
				.andExpect(jsonPath("$.data[0].error").value("Garmin said no"))
				.andExpect(jsonPath("$.data[0].tcx").value(nullValue()));
		mockMvc.perform(get("/v1/garmin/workout-links").header("Authorization", auth))
				.andExpect(jsonPath("$.failed").value(1));
		mockMvc.perform(delete("/v1/garmin/workout-ops/" + opId).header("Authorization", auth))
				.andExpect(status().isNoContent());
		mockMvc.perform(get("/v1/garmin/workout-ops?status=failed").header("Authorization", auth))
				.andExpect(jsonPath("$.data", hasSize(0)));
	}

	@Test
	void resultsAreValidatedAndOnlyTheOwnerCanReportThem() throws Exception {
		User user = athlete("garmin-wpush-validate@example.cc", true);
		User other = athlete("garmin-wpush-validate-other@example.cc", true);
		Workout w = workout(user, "w");
		String opId = JsonPath.read(enqueue(user, upsertBody(w.getId(), TCX)).getResponse().getContentAsString(), "$.id");

		assertThat(report(other, opId, "{\"status\":\"done\",\"garmin_workout_id\":1}")).startsWith("404");
		assertThat(report(user, opId, "{\"status\":\"bogus\"}")).startsWith("400");
		assertThat(report(user, opId, "{\"status\":\"done\"}")).startsWith("400");
		assertThat(report(user, opId, "{\"status\":\"done\",\"garmin_workout_id\":9}")).startsWith("200");
		assertThat(report(user, opId, "{\"status\":\"done\",\"garmin_workout_id\":9}")).startsWith("409");
	}
}
