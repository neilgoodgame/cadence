package com.cadence.api.athletes;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cadence.api.activities.Activity;
import com.cadence.api.activities.ActivityRepository;
import com.cadence.api.activities.BestEffort;
import com.cadence.api.activities.BestEffortKind;
import com.cadence.api.activities.BestEffortRepository;
import com.cadence.api.common.domain.Sport;
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
import org.springframework.test.web.servlet.MockMvc;

/**
 * Regression coverage for {@code GET /v1/athletes/{id}/best-efforts} date-range params over
 * real HTTP - the snake_case query keys this proves (start_date/end_date) were invisible to
 * every other test, which call {@link BestEffortController#listBestEfforts} as a plain Java
 * method and so never exercise Spring's query-string binding. That let a genuine bug ship in
 * #586: the {@code @RequestParam}s had no explicit {@code value = "start_date"}, so they bound
 * the literal camelCase keys and a contract-following client's range silently fell back to
 * all-time data. Same bug class as {@link AthleteControllerZonesIntegrationTest}.
 */
@AutoConfigureMockMvc
class BestEffortDateRangeHttpIntegrationTest extends IntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private ActivityRepository activityRepository;

	@Autowired
	private BestEffortRepository bestEffortRepository;

	@Autowired
	private AccessTokenService accessTokenService;

	private User newAthlete(String email) {
		User user = new User();
		user.setEmail(email);
		user.setName("Athlete");
		user.setPassword("irrelevant-for-this-test");
		user.setBestEffortTopN(10);
		return userRepository.save(user);
	}

	private BestEffort makeEffort(User athlete, double value, long daysAgo) {
		Activity activity = new Activity();
		activity.setAthlete(athlete);
		activity.setSport(Sport.RUN);
		activity.setName("Run -" + daysAgo + "d");
		activity.setStartDate(Instant.now().minusSeconds(daysAgo * 86400));
		activity = activityRepository.save(activity);

		BestEffort effort = new BestEffort();
		effort.setAthlete(athlete);
		effort.setKind(BestEffortKind.RUNNING_PACE);
		effort.setWindow("5km");
		effort.setValue(value);
		effort.setUnit("sec_per_km");
		effort.setDate(LocalDate.now().minusDays(daysAgo));
		effort.setActivity(activity);
		return bestEffortRepository.save(effort);
	}

	private String tokenFor(User athlete) {
		AccessTokenService.CreatedToken token = accessTokenService.create(
				athlete, new CreateAccessTokenRequest("Test token", List.of("activities:read"), null, null), Set.of());
		return token.secret();
	}

	@Test
	void snakeCaseRangeParamsBindAndFilter() throws Exception {
		User athlete = newAthlete("befrange-http-bind@example.cc");
		BestEffort inside = makeEffort(athlete, 300.0, 5);
		// Outside the range - if start_date/end_date were silently unbound, the request would
		// fall back to period mode ("all") and return this effort too, so asserting on the
		// single row is what makes the binding failure visible.
		makeEffort(athlete, 290.0, 40);

		mockMvc.perform(get("/v1/athletes/" + athlete.getId() + "/best-efforts")
						.param("kind", "running_pace")
						.param("start_date", LocalDate.now().minusDays(10).toString())
						.param("end_date", LocalDate.now().minusDays(1).toString())
						.header("Authorization", "Bearer " + tokenFor(athlete)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.period").value(nullValue()))
				.andExpect(jsonPath("$.start_date").value(LocalDate.now().minusDays(10).toString()))
				.andExpect(jsonPath("$.data.length()").value(1))
				.andExpect(jsonPath("$.data[0].activity_id").value(inside.getActivity().getId()))
				.andExpect(jsonPath("$.data[0].activity_name").value(inside.getActivity().getName()));
	}

	@Test
	void oneSidedRangeIsA400OverHttp() throws Exception {
		User athlete = newAthlete("befrange-http-onesided@example.cc");

		mockMvc.perform(get("/v1/athletes/" + athlete.getId() + "/best-efforts")
						.param("kind", "running_pace")
						.param("start_date", LocalDate.now().toString())
						.header("Authorization", "Bearer " + tokenFor(athlete)))
				.andExpect(status().isBadRequest());
	}
}
