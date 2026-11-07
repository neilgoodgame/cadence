package com.cadence.api.activities;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cadence.api.common.domain.Sport;
import com.cadence.api.support.IntegrationTest;
import com.cadence.api.tokens.AccessTokenService;
import com.cadence.api.tokens.dto.CreateAccessTokenRequest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Pins the actual HTTP binding of POST .../merge's JSON body on {@code ActivityController} -
 * {@code ActivityMergeServiceIntegrationTest} calls {@code ActivityMergeService} directly,
 * bypassing the controller and its {@code @Valid}/{@code NullPolicyConverter} layer entirely.
 * See {@code OnOverlapRequestParamIntegrationTest}'s own Javadoc for why that gap matters: a
 * service-level test alone wouldn't have caught #498's on_overlap param-binding bug either.
 */
@AutoConfigureMockMvc
class ActivityMergeControllerIntegrationTest extends IntegrationTest {

	private static final Instant START = Instant.parse("2026-06-01T18:00:00Z");

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private ActivityRepository activityRepository;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private AccessTokenService accessTokenService;

	private User newAthlete(String email) {
		User user = new User();
		user.setEmail(email);
		user.setName("Test User");
		user.setPassword("irrelevant-for-this-test");
		return userRepository.save(user);
	}

	private Activity newActivity(User athlete, String name) {
		Activity activity = new Activity();
		activity.setAthlete(athlete);
		activity.setSport(Sport.BIKE);
		activity.setName(name);
		activity.setStartDate(START);
		return activityRepository.save(activity);
	}

	private String bearer(User user) {
		return "Bearer " + accessTokenService
				.create(user, new CreateAccessTokenRequest("merge-test", List.of("activities:write"), null, null), Set.of())
				.secret();
	}

	@Test
	void mergeAsJsonBindsNullPolicyAndReturnsAResult() throws Exception {
		User athlete = newAthlete("merge-controller@example.cc");
		Activity target = newActivity(athlete, "Target");
		Activity source = newActivity(athlete, "Source");
		// hide_source/preview deliberately omitted - confirms they default to false rather than
		// 400ing (see MergeActivitiesRequest's own Javadoc for why they're boxed, not primitive).
		String body = "{\"source_id\":\"" + source.getId() + "\",\"fields\":[\"name\"],"
				+ "\"null_policy\":\"keep_original\",\"offset_seconds\":0}";

		mockMvc.perform(post("/v1/activities/" + target.getId() + "/merge").header("Authorization", bearer(athlete))
						.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.merge_id").exists())
				.andExpect(jsonPath("$.offset_seconds").value(0))
				.andExpect(jsonPath("$.source_hidden").value(false));
	}

	@Test
	void mergeRejectsAnEmptyFieldList() throws Exception {
		User athlete = newAthlete("merge-controller-empty-fields@example.cc");
		Activity target = newActivity(athlete, "Target");
		Activity source = newActivity(athlete, "Source");
		String body = "{\"source_id\":\"" + source.getId() + "\",\"fields\":[],\"null_policy\":\"overwrite\"}";

		mockMvc.perform(post("/v1/activities/" + target.getId() + "/merge").header("Authorization", bearer(athlete))
						.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isBadRequest());
	}
}
