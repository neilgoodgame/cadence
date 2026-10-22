package com.cadence.api.garmin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cadence.api.support.IntegrationTest;
import com.cadence.api.tokens.AccessTokenService;
import com.cadence.api.tokens.dto.CreateAccessTokenRequest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class GarminConnectionIntegrationTest extends IntegrationTest {

	private static final String KEY = "garmin-connection-test-key-0001";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private AccessTokenService accessTokenService;

	@Autowired
	private GarminConnectionRepository connectionRepository;

	@Autowired
	private GarminKeyCipher cipher;

	private User newAthlete(String email) {
		User user = new User();
		user.setEmail(email);
		user.setName("Athlete");
		user.setPassword("irrelevant-for-this-test");
		return userRepository.save(user);
	}

	private String bearer(User user) {
		return "Bearer " + accessTokenService
				.create(user, new CreateAccessTokenRequest("garmin-conn-test", List.of("activities:read"), null, null), Set.of())
				.secret();
	}

	private String body(String apiKey, String email) {
		StringBuilder sb = new StringBuilder("{");
		if (apiKey != null) {
			sb.append("\"api_key\":\"").append(apiKey).append("\",");
		}
		if (email != null) {
			sb.append("\"email\":\"").append(email).append("\",");
		}
		sb.append("\"key_expires_on\":\"2999-01-01\"}");
		return sb.toString();
	}

	@Test
	void savesAnEncryptedKeyDefaultsTheEmailAndNeverReturnsTheKey() throws Exception {
		User athlete = newAthlete("garmin-conn-default-email@example.cc");

		mockMvc.perform(put("/v1/garmin/connection").header("Authorization", bearer(athlete))
						.contentType(MediaType.APPLICATION_JSON).content(body(KEY, null)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.connected").value(true))
				.andExpect(jsonPath("$.email").value("garmin-conn-default-email@example.cc"))
				.andExpect(jsonPath("$.key_expires_on").value("2999-01-01"))
				.andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(KEY))));

		GarminConnection stored = connectionRepository.findByOwnerId(athlete.getId()).orElseThrow();
		assertThat(stored.getApiKeyEncrypted()).doesNotContain(KEY).startsWith("v1.");
		assertThat(cipher.decrypt(stored.getApiKeyEncrypted(), athlete.getId())).isEqualTo(KEY);
	}

	@Test
	void aDifferentGarminEmailIsKeptAndEditingWithoutAKeyLeavesTheKeyAlone() throws Exception {
		User athlete = newAthlete("garmin-conn-edit@example.cc");
		String auth = bearer(athlete);
		mockMvc.perform(put("/v1/garmin/connection").header("Authorization", auth)
				.contentType(MediaType.APPLICATION_JSON).content(body(KEY, "garmin-account@example.cc")))
				.andExpect(status().isOk());

		mockMvc.perform(put("/v1/garmin/connection").header("Authorization", auth)
						.contentType(MediaType.APPLICATION_JSON).content(body(null, "other-garmin@example.cc")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.email").value("other-garmin@example.cc"));

		GarminConnection stored = connectionRepository.findByOwnerId(athlete.getId()).orElseThrow();
		assertThat(cipher.decrypt(stored.getApiKeyEncrypted(), athlete.getId())).isEqualTo(KEY);
	}

	@Test
	void syncLimitDefaultsToFiftyCanBeSetAndIsLeftAloneWhenOmitted() throws Exception {
		User athlete = newAthlete("garmin-conn-synclimit@example.cc");
		String auth = bearer(athlete);
		mockMvc.perform(put("/v1/garmin/connection").header("Authorization", auth)
						.contentType(MediaType.APPLICATION_JSON).content(body(KEY, null)))
				.andExpect(jsonPath("$.sync_limit").value(50));

		mockMvc.perform(put("/v1/garmin/connection").header("Authorization", auth)
						.contentType(MediaType.APPLICATION_JSON).content("{\"sync_limit\":20}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.sync_limit").value(20));
		mockMvc.perform(put("/v1/garmin/connection").header("Authorization", auth)
						.contentType(MediaType.APPLICATION_JSON).content(body(null, "x@example.cc")))
				.andExpect(jsonPath("$.sync_limit").value(20));
		mockMvc.perform(get("/v1/garmin/connection").header("Authorization", auth))
				.andExpect(jsonPath("$.sync_limit").value(20));
	}

	@Test
	void aSyncLimitOutsideOneToOneHundredIsRejected() throws Exception {
		User athlete = newAthlete("garmin-conn-badlimit@example.cc");
		String auth = bearer(athlete);

		for (int bad : new int[] {0, 101, -5}) {
			mockMvc.perform(put("/v1/garmin/connection").header("Authorization", auth)
							.contentType(MediaType.APPLICATION_JSON)
							.content("{\"api_key\":\"" + KEY + "\",\"sync_limit\":" + bad + "}"))
					.andExpect(status().isBadRequest());
		}
		assertThat(connectionRepository.findByOwnerId(athlete.getId())).isEmpty();
	}

	@Test
	void firstSaveWithoutAKeyIsRejected() throws Exception {
		User athlete = newAthlete("garmin-conn-nokey@example.cc");

		mockMvc.perform(put("/v1/garmin/connection").header("Authorization", bearer(athlete))
						.contentType(MediaType.APPLICATION_JSON).content(body(null, null)))
				.andExpect(status().isBadRequest());
	}

	@Test
	void aPastExpiryDateIsRejected() throws Exception {
		User athlete = newAthlete("garmin-conn-expired@example.cc");

		mockMvc.perform(put("/v1/garmin/connection").header("Authorization", bearer(athlete))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"api_key\":\"" + KEY + "\",\"key_expires_on\":\"2020-01-01\"}"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void deleteRemovesTheConnectionAndOthersCannotSeeIt() throws Exception {
		User athlete = newAthlete("garmin-conn-delete@example.cc");
		User other = newAthlete("garmin-conn-other@example.cc");
		String auth = bearer(athlete);
		mockMvc.perform(put("/v1/garmin/connection").header("Authorization", auth)
				.contentType(MediaType.APPLICATION_JSON).content(body(KEY, null))).andExpect(status().isOk());

		mockMvc.perform(get("/v1/garmin/connection").header("Authorization", bearer(other)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.connected").value(false));

		mockMvc.perform(delete("/v1/garmin/connection").header("Authorization", auth)).andExpect(status().isNoContent());
		mockMvc.perform(get("/v1/garmin/connection").header("Authorization", auth))
				.andExpect(jsonPath("$.connected").value(false));
		assertThat(connectionRepository.findByOwnerId(athlete.getId())).isEmpty();
	}
}
