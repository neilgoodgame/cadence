package com.cadence.api.athletes;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cadence.api.activities.Activity;
import com.cadence.api.activities.ActivityRepository;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.support.IntegrationTest;
import com.cadence.api.tokens.AccessTokenService;
import com.cadence.api.tokens.dto.CreateAccessTokenRequest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Regression coverage for {@code GET /v1/athletes/{id}/zones} over real HTTP - the activity
 * scoping this proves (see {@link ZoneServiceIntegrationTest}) was previously invisible to tests
 * because every other test called {@link AthleteController#listZones} as a plain Java method,
 * never through Spring's actual query-string binding. That let a genuine bug ship: the
 * {@code activityId} {@code @RequestParam} had no explicit {@code name = "activity_id"}, so it
 * never matched the frontend's {@code ?activity_id=} query key, `activity` silently resolved to
 * null on every request, and every activity page's zones (however old) rendered against the
 * athlete's *current* thresholds instead of the historical ones.
 */
@AutoConfigureMockMvc
class AthleteControllerZonesIntegrationTest extends IntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private ActivityRepository activityRepository;

	@Autowired
	private ThresholdHistoryRepository thresholdHistoryRepository;

	@Autowired
	private AccessTokenService accessTokenService;

	private User newAthlete(String email) {
		User user = new User();
		user.setEmail(email);
		user.setName("Athlete");
		user.setPassword("irrelevant-for-this-test");
		user.setFtp(250);
		return userRepository.save(user);
	}

	private Activity newActivity(User athlete, Instant startDate) {
		Activity activity = new Activity();
		activity.setAthlete(athlete);
		activity.setSport(Sport.BIKE);
		activity.setName("Old ride");
		activity.setStartDate(startDate);
		return activityRepository.save(activity);
	}

	private void newFtpEntry(User athlete, Activity sourceActivity, int valueNumeric) {
		ThresholdHistory entry = new ThresholdHistory();
		entry.setAthlete(athlete);
		entry.setField(ThresholdField.FTP);
		entry.setValueNumeric(valueNumeric);
		entry.setValuePace("");
		entry.setSourceActivity(sourceActivity);
		entry.setEffectiveFrom(sourceActivity.getStartDate().atZone(ZoneOffset.UTC).toLocalDate());
		thresholdHistoryRepository.save(entry);
	}

	private String tokenFor(User athlete) {
		AccessTokenService.CreatedToken token = accessTokenService.create(
				athlete, new CreateAccessTokenRequest("Test token", List.of("activities:read"), null, null), Set.of());
		return token.secret();
	}

	@Test
	void activityIdQueryParamScopesTheReferenceToThatActivitysHistoricalThreshold() throws Exception {
		User athlete = newAthlete("zones-http-activity-scope@example.cc");
		Activity oldActivity = newActivity(athlete, Instant.parse("2023-09-03T07:45:51Z"));
		newFtpEntry(athlete, oldActivity, 200);
		// A later ledger entry (and the athlete's current live FTP) supersedes 200 with 250 -
		// asserting on 250 would trivially pass even if `activity_id` were silently ignored.
		Activity laterActivity = newActivity(athlete, Instant.parse("2026-08-15T07:00:00Z"));
		newFtpEntry(athlete, laterActivity, 250);

		mockMvc.perform(get("/v1/athletes/" + athlete.getId() + "/zones")
						.param("activity_id", oldActivity.getId())
						.header("Authorization", "Bearer " + tokenFor(athlete)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[?(@.type=='bike_power')].reference").value(200.0));
	}

	@Test
	void withoutActivityIdTheReferenceIsTheAthletesCurrentProfile() throws Exception {
		User athlete = newAthlete("zones-http-no-activity@example.cc");

		mockMvc.perform(
						get("/v1/athletes/" + athlete.getId() + "/zones").header("Authorization", "Bearer " + tokenFor(athlete)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[?(@.type=='bike_power')].reference").value(250.0));
	}
}
