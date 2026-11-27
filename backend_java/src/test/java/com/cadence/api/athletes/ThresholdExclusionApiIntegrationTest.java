package com.cadence.api.athletes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cadence.api.activities.Activity;
import com.cadence.api.activities.ActivityRepository;
import com.cadence.api.activities.ActivityService;
import com.cadence.api.activities.Record;
import com.cadence.api.activities.RecordId;
import com.cadence.api.activities.RecordRepository;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.support.IntegrationTest;
import com.cadence.api.tokens.AccessTokenService;
import com.cadence.api.tokens.dto.CreateAccessTokenRequest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Issue #616: the REST API over the exclusion over real HTTP - POST excludes and rebuilds from
 * the activity's date (or previews that with nothing saved), DELETE reverses it, GET lists.
 * Mirrors the Python backend's athletes/test_threshold_exclusion.py::ThresholdExclusionApiTests. */
@AutoConfigureMockMvc
class ThresholdExclusionApiIntegrationTest extends IntegrationTest {

	private static final LocalDate GOOD_DAY = LocalDate.of(2026, 3, 1);
	private static final LocalDate BAD_DAY = LocalDate.of(2026, 3, 10);

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private ActivityRepository activityRepository;
	@Autowired
	private RecordRepository recordRepository;
	@Autowired
	private ThresholdHistoryRepository thresholdHistoryRepository;
	@Autowired
	private ExcludedThresholdCandidateRepository excludedRepository;
	@Autowired
	private AcceptedThresholdCandidateRepository acceptedRepository;
	@Autowired
	private ActivityService activityService;
	@Autowired
	private AccessTokenService accessTokenService;

	private record Setup(User athlete, Activity good, Activity bad, String token, String url) {
	}

	private User athlete(String email) {
		User user = new User();
		user.setEmail(email);
		user.setName("Exclusion Athlete");
		user.setPassword("irrelevant-for-this-test");
		user.setThresholdWindowDays(30);
		user.setThresholdSanityPct(50);
		user.setLthr(160);
		return userRepository.save(user);
	}

	private Activity ride(User owner, LocalDate day, int power, Sport sport) {
		Instant start = day.atTime(7, 0).toInstant(ZoneOffset.UTC);
		Activity activity = new Activity();
		activity.setAthlete(owner);
		activity.setSport(sport);
		activity.setName("Ride");
		activity.setStartDate(start);
		activity.setMovingTime(1200);
		activity = activityRepository.save(activity);
		List<Record> records = new ArrayList<>();
		for (int t = 0; t < 1200; t++) {
			Record record = new Record();
			record.setId(new RecordId(activity.getId(), start.plusSeconds(t)));
			record.setActivity(activity);
			record.setT(t);
			record.setPower(power);
			record.setHeartrate(150);
			records.add(record);
		}
		recordRepository.saveAll(records);
		return activity;
	}

	private String tokenFor(User user) {
		return accessTokenService.create(user,
				new CreateAccessTokenRequest("Test token", List.of("activities:read", "activities:write"), null, null), Set.of())
				.secret();
	}

	/** A ledger built from a genuine ride implying 247 W and a bad one implying 304 W. */
	private Setup setup(String email) {
		User owner = athlete(email);
		Activity good = ride(owner, GOOD_DAY, 260, Sport.BIKE);
		Activity bad = ride(owner, BAD_DAY, 320, Sport.BIKE);
		activityService.recomputeAfterDuplicateChange(owner, EnumSet.of(ThresholdField.FTP), null);
		return new Setup(owner, good, bad, tokenFor(owner), "/v1/athletes/" + owner.getId() + "/threshold-exclusions");
	}

	private List<String> ledger(User owner) {
		return thresholdHistoryRepository.findByAthleteIdAndFieldOrderByEffectiveFromDescIdDesc(owner.getId(), ThresholdField.FTP)
				.stream()
				.sorted(Comparator.comparing(ThresholdHistory::getCurrentFrom))
				.map(row -> (row.getSourceActivity() != null ? row.getSourceActivity().getId() : "-") + "@" + row.getValueNumeric()
						+ "@" + row.getCurrentFrom())
				.toList();
	}

	private static String body(String field, String activityId, Boolean preview) {
		return "{\"field\":\"" + field + "\"" + (activityId != null ? ",\"activity_id\":\"" + activityId + "\"" : "")
				+ (preview != null ? ",\"preview\":" + preview : "") + "}";
	}

	private Integer ftp(User owner) {
		return userRepository.findById(owner.getId()).orElseThrow().getFtp();
	}

	@Test
	void previewReportsTheEffectAndSavesNothing() throws Exception {
		Setup s = setup("excl-616-api-preview@example.cc");
		List<String> ledgerBefore = ledger(s.athlete());
		assertThat(ftp(s.athlete())).isEqualTo(304);

		mockMvc.perform(post(s.url()).header("Authorization", "Bearer " + s.token()).contentType(MediaType.APPLICATION_JSON)
						.content(body("ftp", s.bad().getId(), true)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.preview").value(true))
				.andExpect(jsonPath("$.value_before").value(304))
				.andExpect(jsonPath("$.value_after").value(247))
				.andExpect(jsonPath("$.rows_removed[?(@.source_activity_id == '" + s.bad().getId() + "')]").exists());

		assertThat(ledger(s.athlete())).isEqualTo(ledgerBefore);
		assertThat(excludedRepository.findByAthleteIdAndField(s.athlete().getId(), ThresholdField.FTP)).isEmpty();
		assertThat(ftp(s.athlete())).isEqualTo(304);
	}

	@Test
	void excludeRebuildsTheLedgerAndDeleteRestoresIt() throws Exception {
		Setup s = setup("excl-616-api-roundtrip@example.cc");
		List<String> ledgerBefore = ledger(s.athlete());

		mockMvc.perform(post(s.url()).header("Authorization", "Bearer " + s.token()).contentType(MediaType.APPLICATION_JSON)
						.content(body("ftp", s.bad().getId(), null)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.value_after").value(247));

		assertThat(ledger(s.athlete())).noneMatch(row -> row.startsWith(s.bad().getId()));
		assertThat(ftp(s.athlete())).isEqualTo(247);
		List<ExcludedThresholdCandidate> exclusions =
				excludedRepository.findByAthleteIdAndField(s.athlete().getId(), ThresholdField.FTP);
		assertThat(exclusions).hasSize(1);

		mockMvc.perform(delete(s.url() + "/ftp/" + s.bad().getId()).header("Authorization", "Bearer " + s.token()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.value_after").value(304));

		assertThat(ledger(s.athlete())).isEqualTo(ledgerBefore);
		assertThat(excludedRepository.findByAthleteIdAndField(s.athlete().getId(), ThresholdField.FTP)).isEmpty();
	}

	@Test
	void excludingTwiceChangesNothingTheSecondTime() throws Exception {
		Setup s = setup("excl-616-api-twice@example.cc");
		for (int i = 0; i < 2; i++) {
			var result = mockMvc.perform(post(s.url()).header("Authorization", "Bearer " + s.token())
							.contentType(MediaType.APPLICATION_JSON).content(body("ftp", s.bad().getId(), null)))
					.andExpect(status().isOk());
			if (i == 1) {
				result.andExpect(jsonPath("$.rows_removed").isEmpty()).andExpect(jsonPath("$.rows_added").isEmpty());
			}
		}
		assertThat(excludedRepository.findByAthleteIdAndField(s.athlete().getId(), ThresholdField.FTP)).hasSize(1);
	}

	@Test
	void excludingRemovesAnAcceptanceOfTheSameActivity() throws Exception {
		Setup s = setup("excl-616-api-accept@example.cc");
		AcceptedThresholdCandidate accepted = new AcceptedThresholdCandidate();
		accepted.setAthlete(s.athlete());
		accepted.setField(ThresholdField.FTP);
		accepted.setActivity(s.bad());
		acceptedRepository.save(accepted);

		mockMvc.perform(post(s.url()).header("Authorization", "Bearer " + s.token()).contentType(MediaType.APPLICATION_JSON)
						.content(body("ftp", s.bad().getId(), null)))
				.andExpect(status().isOk());

		assertThat(acceptedRepository.findByAthleteIdAndField(s.athlete().getId(), ThresholdField.FTP)).isEmpty();
	}

	@Test
	void listFiltersByFieldAndActivity() throws Exception {
		Setup s = setup("excl-616-api-list@example.cc");
		mockMvc.perform(post(s.url()).header("Authorization", "Bearer " + s.token()).contentType(MediaType.APPLICATION_JSON)
				.content(body("ftp", s.bad().getId(), null))).andExpect(status().isOk());

		mockMvc.perform(get(s.url()).header("Authorization", "Bearer " + s.token()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(1))
				.andExpect(jsonPath("$.data[0].field").value("ftp"))
				.andExpect(jsonPath("$.data[0].activity_id").value(s.bad().getId()))
				.andExpect(jsonPath("$.data[0].set_by_user_id").value(s.athlete().getId()));
		mockMvc.perform(get(s.url()).param("activity_id", s.good().getId()).header("Authorization", "Bearer " + s.token()))
				.andExpect(jsonPath("$.data").isEmpty());
		mockMvc.perform(get(s.url()).param("field", "critical_run_power").header("Authorization", "Bearer " + s.token()))
				.andExpect(jsonPath("$.data").isEmpty());
	}

	@Test
	void rejectsBadInput() throws Exception {
		Setup s = setup("excl-616-api-bad-input@example.cc");
		Activity run = ride(s.athlete(), GOOD_DAY.plusDays(1), 250, Sport.RUN);
		User stranger = athlete("excl-616-api-stranger@example.cc");
		Activity theirs = ride(stranger, GOOD_DAY, 200, Sport.BIKE);

		record Case(String body, int status) {
		}
		for (Case c : List.of(
				new Case(body("vo2max", s.bad().getId(), null), 400),
				new Case(body("ftp", null, null), 400),
				new Case(body("ftp", run.getId(), null), 400), // a run can't define ftp
				new Case(body("ftp", theirs.getId(), null), 404), // another athlete's activity
				new Case(body("ftp", "act_missing", null), 404))) {
			mockMvc.perform(post(s.url()).header("Authorization", "Bearer " + s.token()).contentType(MediaType.APPLICATION_JSON)
					.content(c.body())).andExpect(status().is(c.status()));
		}
		mockMvc.perform(delete(s.url() + "/ftp/" + s.good().getId()).header("Authorization", "Bearer " + s.token()))
				.andExpect(status().isNotFound()); // not excluded
	}

	// Per-athlete access, as for every REST endpoint: neither backend's REST layer checks OAuth
	// scopes (only the MCP tools do), so a stranger is refused on access, not scope.
	@Test
	void needsWriteAccessToTheAthlete() throws Exception {
		Setup s = setup("excl-616-api-access@example.cc");
		String outsiderToken = tokenFor(athlete("excl-616-api-outsider@example.cc"));

		mockMvc.perform(get(s.url()).header("Authorization", "Bearer " + outsiderToken)).andExpect(status().isForbidden());
		mockMvc.perform(post(s.url()).header("Authorization", "Bearer " + outsiderToken).contentType(MediaType.APPLICATION_JSON)
				.content(body("ftp", s.bad().getId(), null))).andExpect(status().isForbidden());
		mockMvc.perform(delete(s.url() + "/ftp/" + s.bad().getId()).header("Authorization", "Bearer " + outsiderToken))
				.andExpect(status().isForbidden());
		assertThat(excludedRepository.findByAthleteIdAndField(s.athlete().getId(), ThresholdField.FTP)).isEmpty();
	}
}
