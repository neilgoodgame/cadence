package com.cadence.api.athletes;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cadence.api.support.IntegrationTest;
import com.cadence.api.tokens.AccessTokenService;
import com.cadence.api.tokens.dto.CreateAccessTokenRequest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserMapper;
import com.cadence.api.users.UserRepository;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Regression coverage for {@code PATCH /v1/athletes/{id}} over real HTTP - every other test
 * exercises {@link AthleteService#updateProfile} or {@link UserMapper#toResponse} as plain Java
 * calls, never through the controller's actual request/response DTOs. That gap let a genuine
 * bug ship (issue #539): {@code UserResponse} never gained a {@code bikePowerSource} field, so
 * {@code bike_power_source} silently never round-tripped through the API - saved on the entity
 * by {@link AthleteService}, but dropped by {@link UserMapper} on the way back out - while the
 * sibling {@code hr_source}/{@code cadence_source} fields (added in the same issue #503 phase)
 * worked fine.
 */
@AutoConfigureMockMvc
class AthleteControllerUpdateIntegrationTest extends IntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private AccessTokenService accessTokenService;

	private User newAthlete(String email) {
		User user = new User();
		user.setEmail(email);
		user.setName("Athlete");
		user.setPassword("irrelevant-for-this-test");
		return userRepository.save(user);
	}

	private String tokenFor(User athlete) {
		AccessTokenService.CreatedToken token = accessTokenService.create(
				athlete, new CreateAccessTokenRequest("Test token", List.of("athletes:write"), null, null), Set.of());
		return token.secret();
	}

	@Test
	void patchingTheSensorSourcePreferencesRoundTripsAllFourThroughTheResponse() throws Exception {
		User athlete = newAthlete("athlete-update-sensor-sources@example.cc");

		mockMvc.perform(patch("/v1/athletes/" + athlete.getId())
						.header("Authorization", "Bearer " + tokenFor(athlete))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "bike_power_source": "pedals",
								  "hr_source": "wrist",
								  "cadence_source": "watch",
								  "running_power_source": "native"
								}
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.bike_power_source").value("pedals"))
				.andExpect(jsonPath("$.hr_source").value("wrist"))
				.andExpect(jsonPath("$.cadence_source").value("watch"))
				.andExpect(jsonPath("$.running_power_source").value("native"));

		mockMvc.perform(get("/v1/athletes/" + athlete.getId())
						.header("Authorization", "Bearer " + tokenFor(athlete)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.bike_power_source").value("pedals"));
	}
}
