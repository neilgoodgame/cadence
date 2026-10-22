package com.cadence.api.garmin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cadence.api.support.IntegrationTest;
import com.cadence.api.tokens.AccessTokenService;
import com.cadence.api.tokens.dto.CreateAccessTokenRequest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Runs the real sync (and the real batch upload pipeline behind it) against a stub of Garmin's
 * Personal API: three activities backed by two real FIT fixtures.
 */
@AutoConfigureMockMvc
class GarminSyncIntegrationTest extends IntegrationTest {

	private static final String GOOD_KEY = "garmin-sync-test-key-0001";
	private static final HttpServer GARMIN = startStubGarmin();

	@DynamicPropertySource
	static void garminBaseUrl(DynamicPropertyRegistry registry) {
		registry.add("cadence.garmin.api-base-url", () -> "http://localhost:" + GARMIN.getAddress().getPort());
	}

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private AccessTokenService accessTokenService;

	@Autowired
	private GarminConnectionRepository connectionRepository;

	@Autowired
	private GarminConnectionService connectionService;

	@Autowired
	private GarminSyncRunRepository runRepository;

	@Autowired
	private com.cadence.api.activities.ActivityRepository activityRepository;

	@Autowired
	private com.cadence.api.uploads.UploadService uploadService;

	@Autowired
	private com.cadence.api.gear.ShoeRepository shoeRepository;

	@Autowired
	private com.cadence.api.gear.ShoeModelRepository shoeModelRepository;

	@Autowired
	private com.cadence.api.gear.ShoeModelVersionRepository shoeModelVersionRepository;

	private static HttpServer startStubGarmin() {
		try {
			HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
			server.createContext("/personalapitoken-service/personal-api/personal-api-key/token", ex -> {
				boolean ok = ("ApiKey " + GOOD_KEY).equals(ex.getRequestHeaders().getFirst("Authorization"));
				respond(ex, ok ? 200 : 401, "text/plain", ok ? "stub-jwt" : "nope");
			});
			server.createContext("/activitylist-service/activities/search/activities", ex -> {
				boolean firstPage = ex.getRequestURI().getQuery().contains("start=0");
				// Newest first, like Garmin, which honours `limit`.
				boolean one = ex.getRequestURI().getQuery().contains("limit=1");
				respond(ex, 200, "application/json", firstPage && one
						? "[{\"activityId\":103,\"startTimeGMT\":\"2026-10-03 10:00:00\",\"activityName\":\"c\"}]"
						: firstPage
						? "[{\"activityId\":103,\"startTimeGMT\":\"2026-10-03 10:00:00\",\"activityName\":\"c\"},"
								+ "{\"activityId\":102,\"startTimeGMT\":\"2026-10-02 10:00:00\",\"activityName\":\"b\"},"
								+ "{\"activityId\":101,\"startTimeGMT\":\"2026-10-01 10:00:00\",\"activityName\":\"a\"}]"
						: "[]");
			});
			server.createContext("/download-service/files/activity/", ex -> {
				String id = ex.getRequestURI().getPath().substring(ex.getRequestURI().getPath().lastIndexOf('/') + 1);
				String fixture = "101".equals(id) ? "indoor_ride_zwift_core_sensor.fit" : "running_outdoor_marathon.fit";
				byte[] fit;
				try (var in = GarminSyncIntegrationTest.class.getResourceAsStream("/fit-fixtures/" + fixture)) {
					fit = in.readAllBytes();
				}
				ByteArrayOutputStream zip = new ByteArrayOutputStream();
				try (ZipOutputStream out = new ZipOutputStream(zip)) {
					out.putNextEntry(new ZipEntry(id + "_ACTIVITY.fit"));
					out.write(fit);
					out.closeEntry();
				}
				ex.getResponseHeaders().add("Content-Type", "application/zip");
				ex.sendResponseHeaders(200, zip.size());
				ex.getResponseBody().write(zip.toByteArray());
				ex.close();
			});
			server.start();
			return server;
		}
		catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	private static void respond(com.sun.net.httpserver.HttpExchange ex, int code, String type, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		ex.getResponseHeaders().add("Content-Type", type);
		ex.sendResponseHeaders(code, bytes.length);
		ex.getResponseBody().write(bytes);
		ex.close();
	}

	private User newAthlete(String email) {
		User user = new User();
		user.setEmail(email);
		user.setName("Athlete");
		user.setPassword("irrelevant-for-this-test");
		return userRepository.save(user);
	}

	private com.cadence.api.gear.Shoe newShoe(User athlete) {
		var model = new com.cadence.api.gear.ShoeModel();
		model.setManufacturer("GarminSyncCo");
		model.setModel("Runner");
		model = shoeModelRepository.save(model);
		var version = new com.cadence.api.gear.ShoeModelVersion();
		version.setShoeModel(model);
		version.setVersion("v1");
		version = shoeModelVersionRepository.save(version);
		var shoe = new com.cadence.api.gear.Shoe();
		shoe.setAthlete(athlete);
		shoe.setShoeModelVersion(version);
		shoe.setName("Garmin sync trainer");
		return shoe;
	}

	private String bearer(User user) {
		return "Bearer " + accessTokenService
				.create(user, new CreateAccessTokenRequest("garmin-sync-test", List.of("activities:read"), null, null), Set.of())
				.secret();
	}

	private User connectedAthlete(String email, String key) {
		User athlete = newAthlete(email);
		connectionService.save(athlete, key, null, null, null);
		return athlete;
	}

	private String syncBody(String json, User athlete) throws Exception {
		MvcResult result = mockMvc.perform(post("/v1/garmin/sync").header("Authorization", bearer(athlete))
				.contentType(MediaType.APPLICATION_JSON).content(json)).andReturn();
		return result.getResponse().getStatus() + " " + result.getResponse().getContentAsString();
	}

	private void awaitBatch(User athlete, String batchId) throws Exception {
		String auth = bearer(athlete);
		for (int i = 0; i < 120; i++) {
			MvcResult r = mockMvc.perform(get("/v1/uploads/batches/" + batchId).header("Authorization", auth)).andReturn();
			if (r.getResponse().getContentAsString().contains("\"status\":\"completed\"")) {
				return;
			}
			Thread.sleep(500);
		}
		throw new AssertionError("batch " + batchId + " did not complete");
	}

	private String syncAndAwait(User athlete) throws Exception {
		MvcResult result = mockMvc.perform(post("/v1/garmin/sync").header("Authorization", bearer(athlete))
						.contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.object").value("garmin_sync"))
				.andExpect(jsonPath("$.found").value(3))
				.andExpect(jsonPath("$.requested").value(3))
				.andExpect(jsonPath("$.failed").value(0))
				.andExpect(jsonPath("$.truncated").value(false))
				.andExpect(jsonPath("$.batch.object").value("upload_batch"))
				.andReturn();
		String batchId = result.getResponse().getContentAsString()
				.replaceAll("(?s).*\"batch\":\\{\"id\":\"([^\"]+)\".*", "$1");
		awaitBatch(athlete, batchId);
		return batchId;
	}

	@Test
	void importsThroughTheRealPipelineAndASecondSyncSkipsEverythingAsDuplicates() throws Exception {
		User athlete = connectedAthlete("garmin-sync-happy@example.cc", GOOD_KEY);

		String first = syncAndAwait(athlete);
		mockMvc.perform(get("/v1/uploads/batches/" + first).header("Authorization", bearer(athlete)))
				.andExpect(jsonPath("$.counts.total").value(3))
				.andExpect(jsonPath("$.counts.ready").value(3))
				.andExpect(jsonPath("$.counts.duplicate").value(0));
		assertThat(connectionRepository.findByOwnerId(athlete.getId()).orElseThrow().getLastSyncedAt()).isNotNull();

		// Every id is now known, so nothing is downloaded or imported - not even as duplicates.
		mockMvc.perform(post("/v1/garmin/sync").header("Authorization", bearer(athlete))
						.contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.found").value(3))
				.andExpect(jsonPath("$.already_synced").value(3))
				.andExpect(jsonPath("$.requested").value(0))
				.andExpect(jsonPath("$.batch").value(nullValue()));
	}

	@Test
	void aDeletedActivityIsNotBroughtBackByTheNextSync() throws Exception {
		User athlete = connectedAthlete("garmin-sync-deleted@example.cc", GOOD_KEY);
		String batchId = syncAndAwait(athlete);
		var uploads = uploadService.getBatchChildren(batchId);
		assertThat(uploads).hasSize(3);
		uploads.forEach(u -> activityRepository.deleteById(u.getActivity().getId()));

		mockMvc.perform(post("/v1/garmin/sync").header("Authorization", bearer(athlete))
						.contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(jsonPath("$.already_synced").value(3))
				.andExpect(jsonPath("$.requested").value(0))
				.andExpect(jsonPath("$.batch").value(nullValue()));
		assertThat(uploadService.getBatchChildren(batchId).stream().filter(u -> u.getActivity() != null)).isEmpty();
	}

	@Test
	void historyRecordsWhatEachRunSyncedAndTheConnectionExposesTheLatest() throws Exception {
		User athlete = connectedAthlete("garmin-sync-history@example.cc", GOOD_KEY);
		String auth = bearer(athlete);
		syncAndAwait(athlete);

		mockMvc.perform(get("/v1/garmin/syncs").header("Authorization", auth))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data", hasSize(1)))
				.andExpect(jsonPath("$.data[0].found").value(3))
				.andExpect(jsonPath("$.data[0].error").value(nullValue()))
				.andExpect(jsonPath("$.data[0].batch.counts.ready").value(3))
				.andExpect(jsonPath("$.data[0].activities", hasSize(3)))
				// Oldest first, each resolved to its imported Cadence activity.
				.andExpect(jsonPath("$.data[0].activities[0].garmin_activity_id").value(101))
				.andExpect(jsonPath("$.data[0].activities[0].upload_status").value("ready"))
				.andExpect(jsonPath("$.data[0].activities[0].activity_id").value(notNullValue()))
				.andExpect(jsonPath("$.data[0].activities[0].sport").value(notNullValue()));

		mockMvc.perform(get("/v1/garmin/connection").header("Authorization", auth))
				.andExpect(jsonPath("$.last_run_at").value(notNullValue()))
				.andExpect(jsonPath("$.last_activity.garmin_activity_id").value(103))
				.andExpect(jsonPath("$.last_activity.start_time").value("2026-10-03T10:00:00Z"));
	}

	@Test
	void aFailedRunIsRecordedWithItsReasonButNeverTheKey() throws Exception {
		User athlete = connectedAthlete("garmin-sync-failedrun@example.cc", "garmin-sync-WRONG-key-9999");
		String auth = bearer(athlete);
		assertThat(syncBody("{}", athlete)).startsWith("422");

		String history = mockMvc.perform(get("/v1/garmin/syncs").header("Authorization", auth))
				.andExpect(jsonPath("$.data", hasSize(1)))
				.andExpect(jsonPath("$.data[0].error").value(notNullValue()))
				.andExpect(jsonPath("$.data[0].batch").value(nullValue()))
				.andReturn().getResponse().getContentAsString();
		assertThat(history).contains("rejected").doesNotContain("WRONG-key");
	}

	@Test
	void onlyTheMostRecentFiftyRunsAreKept() throws Exception {
		User athlete = connectedAthlete("garmin-sync-prune@example.cc", GOOD_KEY);
		for (int i = 0; i < 52; i++) {
			GarminSyncRun old = new GarminSyncRun();
			old.setOwner(athlete);
			old.setStartedAt(Instant.parse("2026-01-01T00:00:00Z").plusSeconds(i));
			runRepository.save(old);
		}

		syncBody("{}", athlete);

		assertThat(runRepository.findByOwnerIdOrderByStartedAtDesc(athlete.getId())).hasSize(50);
	}

	@Test
	void aLimitTakesTheOldestActivitiesAndHoldsTheMarkerAtTheLastOneTaken() throws Exception {
		User athlete = connectedAthlete("garmin-sync-limit@example.cc", GOOD_KEY);

		mockMvc.perform(post("/v1/garmin/sync").header("Authorization", bearer(athlete))
						.contentType(MediaType.APPLICATION_JSON).content("{\"limit\":2}"))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.found").value(3))
				.andExpect(jsonPath("$.requested").value(2))
				.andExpect(jsonPath("$.truncated").value(true));

		assertThat(connectionRepository.findByOwnerId(athlete.getId()).orElseThrow().getLastSyncedAt())
				.isEqualTo(Instant.parse("2026-10-02T10:00:00Z"));
	}

	@Test
	void aLimitAboveTheHardMaximumIsRejected() throws Exception {
		User athlete = connectedAthlete("garmin-sync-cap@example.cc", GOOD_KEY);

		assertThat(syncBody("{\"limit\":101}", athlete)).startsWith("400");
		assertThat(syncBody("{\"limit\":0}", athlete)).startsWith("400");
	}

	@Test
	void theAthletesSavedSyncLimitIsTheDefaultForARun() throws Exception {
		User athlete = connectedAthlete("garmin-sync-savedlimit@example.cc", GOOD_KEY);
		connectionService.save(athlete, null, null, null, 2);

		mockMvc.perform(post("/v1/garmin/sync").header("Authorization", bearer(athlete))
						.contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.requested").value(2))
				.andExpect(jsonPath("$.truncated").value(true));
	}

	@Test
	void latestSyncsOnlyTheNewestActivityWithItsSessionDetailsAndLeavesTheWindowAlone() throws Exception {
		User athlete = connectedAthlete("garmin-sync-latest@example.cc", GOOD_KEY);
		var shoe = shoeRepository.save(newShoe(athlete));

		MvcResult result = mockMvc.perform(post("/v1/garmin/sync").header("Authorization", bearer(athlete))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"latest\":true,\"weight_before_kg\":72.4,\"weight_after_kg\":71.0,"
								+ "\"fluids_ml\":750,\"shoe_id\":\"" + shoe.getId() + "\"}"))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.found").value(1))
				.andExpect(jsonPath("$.requested").value(1))
				.andExpect(jsonPath("$.truncated").value(false))
				.andReturn();
		String batchId = result.getResponse().getContentAsString()
				.replaceAll("(?s).*\"batch\":\\{\"id\":\"([^\"]+)\".*", "$1");
		awaitBatch(athlete, batchId);

		var uploads = uploadService.getBatchChildren(batchId);
		assertThat(uploads).hasSize(1);
		assertThat(uploads.get(0).getFilename()).isEqualTo("103_ACTIVITY.fit");
		var activity = activityRepository.findById(uploads.get(0).getActivity().getId()).orElseThrow();
		assertThat(activity.getStartWeightKg()).isEqualTo(72.4);
		assertThat(activity.getEndWeightKg()).isEqualTo(71.0);
		assertThat(activity.getFluidsMl()).isEqualTo(750);
		assertThat(connectionRepository.findByOwnerId(athlete.getId()).orElseThrow().getLastSyncedAt()).isNull();

		// The same activity again is skipped; a normal sync then picks up only the two older ones.
		mockMvc.perform(post("/v1/garmin/sync").header("Authorization", bearer(athlete))
						.contentType(MediaType.APPLICATION_JSON).content("{\"latest\":true}"))
				.andExpect(jsonPath("$.already_synced").value(1))
				.andExpect(jsonPath("$.requested").value(0))
				.andExpect(jsonPath("$.batch").value(nullValue()));
		mockMvc.perform(post("/v1/garmin/sync").header("Authorization", bearer(athlete))
						.contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(jsonPath("$.found").value(3))
				.andExpect(jsonPath("$.already_synced").value(1))
				.andExpect(jsonPath("$.requested").value(2));
	}

	@Test
	void sessionDetailsWithoutLatestAreRejected() throws Exception {
		User athlete = connectedAthlete("garmin-sync-detailsonly@example.cc", GOOD_KEY);

		assertThat(syncBody("{\"weight_before_kg\":72.4}", athlete)).startsWith("400");
	}

	@Test
	void anUnknownShoeIsRejectedBeforeAnyRunIsRecorded() throws Exception {
		User athlete = connectedAthlete("garmin-sync-badshoe@example.cc", GOOD_KEY);

		assertThat(syncBody("{\"latest\":true,\"shoe_id\":\"shoe_nope\"}", athlete)).startsWith("400");
		assertThat(runRepository.findByOwnerIdOrderByStartedAtDesc(athlete.getId())).isEmpty();
	}

	@Test
	void aFutureSinceDateIsRejected() throws Exception {
		User athlete = connectedAthlete("garmin-sync-future@example.cc", GOOD_KEY);

		assertThat(syncBody("{\"since\":\"" + LocalDate.now().plusDays(2) + "\"}", athlete)).startsWith("400");
	}

	@Test
	void withoutAConnectionItIs404() throws Exception {
		User athlete = newAthlete("garmin-sync-noconn@example.cc");

		assertThat(syncBody("{}", athlete)).startsWith("404");
	}

	@Test
	void anExpiredKeyIs409WithoutCallingGarmin() throws Exception {
		User athlete = connectedAthlete("garmin-sync-expired@example.cc", GOOD_KEY);
		GarminConnection connection = connectionRepository.findByOwnerId(athlete.getId()).orElseThrow();
		connection.setKeyExpiresOn(LocalDate.now().minusDays(1));
		connectionRepository.save(connection);

		assertThat(syncBody("{}", athlete)).startsWith("409");
	}

	@Test
	void aKeyGarminRejectsIs422AndTheKeyIsNeverEchoed() throws Exception {
		User athlete = connectedAthlete("garmin-sync-badkey@example.cc", "garmin-sync-WRONG-key-9999");

		String response = syncBody("{}", athlete);
		assertThat(response).startsWith("422");
		assertThat(response).doesNotContain("WRONG-key");
		assertThat(connectionRepository.findByOwnerId(athlete.getId()).orElseThrow().getLastSyncedAt()).isNull();
	}
}
