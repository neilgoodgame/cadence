package com.cadence.api.activities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cadence.api.athletes.HrSource;
import com.cadence.api.athletes.RunningPowerSource;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.common.error.ValidationException;
import com.cadence.api.support.IntegrationTest;
import com.cadence.api.tokens.AccessTokenService;
import com.cadence.api.tokens.dto.CreateAccessTokenRequest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import java.lang.reflect.Field;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** Issue #540 (phase 5): mirrors backend's activities/tests/test_agreement_trend_service.py. */
@AutoConfigureMockMvc
class AgreementTrendServiceIntegrationTest extends IntegrationTest {

	// 60 paired seconds clears StreamComparisonCalculator.MIN_OVERLAP_SAMPLES (30) with room to
	// spare.
	private static final int SAMPLES = 60;

	private static final Instant START = Instant.parse("2026-06-03T09:00:00Z");

	@Autowired
	private AgreementTrendService agreementTrendService;
	@Autowired
	private ActivityRepository activityRepository;
	@Autowired
	private RecordRepository recordRepository;
	@Autowired
	private ActivitySourceRepository sourceRepository;
	@Autowired
	private ActivityStreamRepository streamRepository;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private AccessTokenService accessTokenService;
	@Autowired
	private MockMvc mockMvc;

	private User newAthlete(String email) {
		User user = new User();
		user.setEmail(email);
		user.setName("Athlete");
		user.setPassword("irrelevant-for-this-test");
		return userRepository.save(user);
	}

	private Activity newBikeActivity(User athlete, String name, String device) {
		return newBikeActivity(athlete, name, device, START);
	}

	private Activity newBikeActivity(User athlete, String name, String device, Instant startDate) {
		Activity activity = new Activity();
		activity.setAthlete(athlete);
		activity.setSport(Sport.BIKE);
		activity.setName(name);
		activity.setDevice(device);
		activity.setStartDate(startDate);
		return activityRepository.save(activity);
	}

	private Activity newRunActivity(User athlete, String name) {
		Activity activity = new Activity();
		activity.setAthlete(athlete);
		activity.setSport(Sport.RUN);
		activity.setName(name);
		activity.setStartDate(START);
		return activityRepository.save(activity);
	}

	private void addPowerRecords(Activity activity, int count, int baseValue) {
		addFieldSeries(activity, "power", count, baseValue);
	}

	private void addHeartrateRecords(Activity activity, int count, int baseValue) {
		addFieldSeries(activity, "heartrate", count, baseValue);
	}

	/** Writes a linearly-varying series ({@code baseValue + t}) instead of a constant one - a
	 * constant series has zero variance and can't exercise Pearson r/the lag search meaningfully. */
	private void addFieldSeries(Activity activity, String field, int count, int baseValue) {
		List<Record> records = new ArrayList<>();
		for (int t = 0; t < count; t++) {
			Record record = new Record();
			record.setId(new RecordId(activity.getId(), activity.getStartDate().plusSeconds(t)));
			record.setActivity(activity);
			record.setT(t);
			ActivityMergeApplyService.setRecordFieldValue(record, field, baseValue + t);
			records.add(record);
		}
		recordRepository.saveAll(records);
	}

	private void addRecordsWith(Activity activity, int count, BiConsumer<Record, Integer> customize) {
		List<Record> records = new ArrayList<>();
		for (int t = 0; t < count; t++) {
			Record record = new Record();
			record.setId(new RecordId(activity.getId(), activity.getStartDate().plusSeconds(t)));
			record.setActivity(activity);
			record.setT(t);
			customize.accept(record, t);
			records.add(record);
		}
		recordRepository.saveAll(records);
	}

	private ActivitySource newStoredSource(Activity activity, String field, int count, int baseValue,
			String device, ActivitySourceOrigin origin, String sourceKind) {
		return newStoredSource(activity, field, count, baseValue, device, origin, sourceKind, null);
	}

	private ActivitySource newStoredSource(Activity activity, String field, int count, int baseValue,
			String device, ActivitySourceOrigin origin, String sourceKind, Instant createdAt) {
		ActivitySource source = new ActivitySource();
		source.setActivity(activity);
		source.setLabel("Stored");
		source.setDevice(device);
		source.setSensorKind(field);
		source.setOrigin(origin);
		if (sourceKind != null) {
			source.setSourceKind(sourceKind);
		}
		if (createdAt != null) {
			setCreatedAt(source, createdAt);
		}
		source = sourceRepository.save(source);

		ActivityStream stream = new ActivityStream();
		stream.setActivity(activity);
		stream.setField(field);
		stream.setSource(source);
		stream.setT0(0);
		Short[] values = new Short[count];
		for (int t = 0; t < count; t++) {
			values[t] = (short) (baseValue + t);
		}
		stream.setStreamValues(values);
		stream.setPrimaryOf(false);
		streamRepository.save(stream);
		return source;
	}

	private static void setCreatedAt(ActivitySource source, Instant createdAt) {
		// No setter - @PrePersist only fills createdAt when null, so set the field directly
		// before saving for a deterministic ordering.
		try {
			Field field = ActivitySource.class.getDeclaredField("createdAt");
			field.setAccessible(true);
			field.set(source, createdAt);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	private AgreementTrendService.AgreementTrendResult trend(User athlete, String field, String deviceA,
			String deviceB, String sport) {
		return agreementTrendService.computeAgreementTrend(athlete.getId(), field, deviceA, deviceB, sport,
				LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30), true, false);
	}

	private String tokenFor(User athlete) {
		AccessTokenService.CreatedToken token = accessTokenService.create(athlete,
				new CreateAccessTokenRequest("Test token", List.of("activities:read"), null, null), Set.of());
		return token.secret();
	}

	@Test
	void primaryTagResolvesToTheRecordSeries() {
		User athlete = newAthlete("agreement-trend-primary@example.cc");
		// device "Zwift" classifies as trainer, so the primary (Record) carries tag "trainer".
		Activity activity = newBikeActivity(athlete, "Ride", "Zwift");
		addPowerRecords(activity, SAMPLES, 200);
		newStoredSource(activity, "power", SAMPLES, 210, "Edge 530", ActivitySourceOrigin.ATTACHED, null);

		AgreementTrendService.AgreementTrendResult result = trend(athlete, "power", "trainer", "pedals", "bike");

		assertThat(result.points()).hasSize(1);
		AgreementTrendService.AgreementTrendPoint point = result.points().get(0);
		assertThat(point.activityId()).isEqualTo(activity.getId());
		assertThat(point.name()).isEqualTo("Ride");
		assertThat(point.date()).isEqualTo(LocalDate.of(2026, 6, 3));
		assertThat(point.overlapSeconds()).isEqualTo(SAMPLES);
		// bias = mean(b - a) = mean((210+t) - (200+t))
		assertThat(point.bias()).isEqualTo(10.0);
		assertThat(result.skipped()).containsEntry("no_device_a", 0)
			.containsEntry("no_device_b", 0)
			.containsEntry("insufficient_overlap", 0);
	}

	@Test
	void storedSourceTagResolvesOnEitherSide() {
		User athlete = newAthlete("agreement-trend-either-side@example.cc");
		Activity activity = newBikeActivity(athlete, "Ride", "Zwift");
		addPowerRecords(activity, SAMPLES, 200);
		newStoredSource(activity, "power", SAMPLES, 210, "Edge 530", ActivitySourceOrigin.ATTACHED, null);

		AgreementTrendService.AgreementTrendResult result = trend(athlete, "power", "pedals", "trainer", "bike");

		// Same pair, sides swapped - bias flips sign.
		assertThat(result.points()).hasSize(1);
		assertThat(result.points().get(0).bias()).isEqualTo(-10.0);
	}

	@Test
	void noMatchSkipsTheActivity() {
		User athlete = newAthlete("agreement-trend-no-match@example.cc");
		// Edge 530 classifies as pedals; with no stored source, tag "trainer" resolves to nothing.
		Activity activity = newBikeActivity(athlete, "Ride", "Edge 530");
		addPowerRecords(activity, SAMPLES, 200);

		AgreementTrendService.AgreementTrendResult result = trend(athlete, "power", "trainer", "pedals", "bike");

		assertThat(result.points()).isEmpty();
		assertThat(result.skipped()).containsEntry("no_device_a", 1)
			.containsEntry("no_device_b", 0)
			.containsEntry("insufficient_overlap", 0);
	}

	@Test
	void mostRecentlyCreatedSourceWinsForAnAmbiguousTag() {
		User athlete = newAthlete("agreement-trend-ambiguous@example.cc");
		Activity activity = newBikeActivity(athlete, "Ride", "Zwift");
		addPowerRecords(activity, SAMPLES, 200);
		newStoredSource(activity, "power", SAMPLES, 205, "Edge 530", ActivitySourceOrigin.ATTACHED, null,
				Instant.parse("2026-06-01T00:00:00Z"));
		newStoredSource(activity, "power", SAMPLES, 210, "Edge 530", ActivitySourceOrigin.ATTACHED, null,
				Instant.parse("2026-06-02T00:00:00Z"));

		AgreementTrendService.AgreementTrendResult result = trend(athlete, "power", "trainer", "pedals", "bike");

		// The newer source (+10) wins, not the older one (+5).
		assertThat(result.points().get(0).bias()).isEqualTo(10.0);
	}

	@Test
	void sportIsRequiredForPower() {
		User athlete = newAthlete("agreement-trend-sport-required@example.cc");

		assertThatThrownBy(() -> trend(athlete, "power", "trainer", "pedals", null))
			.isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.getParam()).isEqualTo("sport"));
		assertThatThrownBy(() -> trend(athlete, "power", "trainer", "pedals", "swim"))
			.isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.getParam()).isEqualTo("sport"));
	}

	@Test
	void deviceTagsAreValidatedAgainstTheFieldVocabulary() {
		User athlete = newAthlete("agreement-trend-vocab@example.cc");

		assertThatThrownBy(() -> trend(athlete, "heartrate", "trainer", "wrist", null))
			.isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.getParam()).isEqualTo("device_a"));
		assertThatThrownBy(() -> trend(athlete, "power", "native", "pedals", "bike"))
			.isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.getParam()).isEqualTo("device_a"));
	}

	@Test
	void identicalDeviceTagsAreRejected() {
		User athlete = newAthlete("agreement-trend-identical-tags@example.cc");

		assertThatThrownBy(() -> trend(athlete, "power", "pedals", "pedals", "bike"))
			.isInstanceOfSatisfying(ValidationException.class, e -> {
				assertThat(e.getParam()).isEqualTo("device_b");
				assertThat(e.getMessage())
					.isEqualTo("device_a and device_b must be two different device tags.");
			});
	}

	@Test
	void badFieldIsRejected() {
		User athlete = newAthlete("agreement-trend-bad-field@example.cc");

		assertThatThrownBy(() -> trend(athlete, "bogus", "a", "b", null))
			.isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.getParam()).isEqualTo("field"));
	}

	@Test
	void skipReasonTallying() {
		User athlete = newAthlete("agreement-trend-skip-tally@example.cc");
		// Point: Zwift primary (trainer) + pedals source, full overlap.
		Activity good = newBikeActivity(athlete, "Good", "Zwift");
		addPowerRecords(good, SAMPLES, 200);
		newStoredSource(good, "power", SAMPLES, 210, "Edge 530", ActivitySourceOrigin.ATTACHED, null);
		// no_device_a: pedals primary, nothing stored as trainer.
		Activity noA = newBikeActivity(athlete, "No A", "Edge 530");
		addPowerRecords(noA, SAMPLES, 200);
		// insufficient_overlap: both tags resolve but only 10 paired seconds.
		Activity shortOverlap = newBikeActivity(athlete, "Short", "Zwift");
		addPowerRecords(shortOverlap, SAMPLES, 200);
		newStoredSource(shortOverlap, "power", 10, 210, "Edge 530", ActivitySourceOrigin.ATTACHED, null);
		// no_device_b: trainer primary, nothing stored as pedals.
		Activity noB = newBikeActivity(athlete, "No B", "Zwift");
		addPowerRecords(noB, SAMPLES, 200);

		AgreementTrendService.AgreementTrendResult result = trend(athlete, "power", "trainer", "pedals", "bike");

		assertThat(result.points())
			.extracting(AgreementTrendService.AgreementTrendPoint::activityId)
			.containsExactly(good.getId());
		assertThat(result.skipped()).containsEntry("no_device_a", 1)
			.containsEntry("no_device_b", 1)
			.containsEntry("insufficient_overlap", 1);
	}

	@Test
	void heartrateTagsUseSourceKindNotDevice() {
		User athlete = newAthlete("agreement-trend-heartrate@example.cc");
		Activity activity = newRunActivity(athlete, "Run");
		activity.setHrSource(HrSource.WRIST);
		activityRepository.save(activity);
		addHeartrateRecords(activity, SAMPLES, 150);
		newStoredSource(activity, "heartrate", SAMPLES, 155, "Garmin Edge 830", ActivitySourceOrigin.ATTACHED,
				"strap");

		// sport is optional for heartrate - omit it.
		AgreementTrendService.AgreementTrendResult result = trend(athlete, "heartrate", "wrist", "strap", null);

		assertThat(result.points()).hasSize(1);
		assertThat(result.points().get(0).bias()).isEqualTo(5.0);
		assertThat(result.skipped()).containsEntry("no_device_a", 0)
			.containsEntry("no_device_b", 0)
			.containsEntry("insufficient_overlap", 0);
	}

	@Test
	void runPowerTagsUseSourceKind() {
		User athlete = newAthlete("agreement-trend-run-power@example.cc");
		Activity activity = newRunActivity(athlete, "Run");
		activity.setPowerSource(RunningPowerSource.STRYD);
		activityRepository.save(activity);
		addPowerRecords(activity, SAMPLES, 250);
		newStoredSource(activity, "power", SAMPLES, 260, "Garmin Forerunner 965", ActivitySourceOrigin.FILE,
				"native");

		AgreementTrendService.AgreementTrendResult result = trend(athlete, "power", "stryd", "native", "run");

		assertThat(result.points()).hasSize(1);
		assertThat(result.points().get(0).bias()).isEqualTo(10.0);
	}

	@Test
	void duplicatesAndMultisportChildrenAreExcludedByDefault() {
		User athlete = newAthlete("agreement-trend-duplicates@example.cc");
		Activity primary = newBikeActivity(athlete, "Primary", "Zwift");
		addPowerRecords(primary, SAMPLES, 200);
		newStoredSource(primary, "power", SAMPLES, 210, "Edge 530", ActivitySourceOrigin.ATTACHED, null);
		Activity duplicate = newBikeActivity(athlete, "Duplicate", "Zwift", START.plusSeconds(86400));
		duplicate.setPrimaryActivity(primary);
		activityRepository.save(duplicate);
		addPowerRecords(duplicate, SAMPLES, 200);
		newStoredSource(duplicate, "power", SAMPLES, 210, "Edge 530", ActivitySourceOrigin.ATTACHED, null);
		Activity parent = new Activity();
		parent.setAthlete(athlete);
		parent.setSport(Sport.MULTISPORT);
		parent.setName("Tri");
		parent.setStartDate(START.plusSeconds(2 * 86400));
		parent = activityRepository.save(parent);
		Activity child = newBikeActivity(athlete, "Leg", "Zwift", START.plusSeconds(2 * 86400));
		child.setParentActivity(parent);
		activityRepository.save(child);
		addPowerRecords(child, SAMPLES, 200);
		newStoredSource(child, "power", SAMPLES, 210, "Edge 530", ActivitySourceOrigin.ATTACHED, null);

		assertThat(trend(athlete, "power", "trainer", "pedals", "bike").points())
			.extracting(AgreementTrendService.AgreementTrendPoint::activityId)
			.containsExactly(primary.getId());

		assertThat(agreementTrendService.computeAgreementTrend(athlete.getId(), "power", "trainer", "pedals",
				"bike", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30), true, true).points())
			.extracting(AgreementTrendService.AgreementTrendPoint::activityId)
			.containsExactlyInAnyOrder(primary.getId(), duplicate.getId());
	}

	@Test
	void dateRangeFiltersActivities() {
		User athlete = newAthlete("agreement-trend-date-range@example.cc");
		Activity outside = newBikeActivity(athlete, "January", "Zwift", Instant.parse("2026-01-05T09:00:00Z"));
		addPowerRecords(outside, SAMPLES, 200);
		newStoredSource(outside, "power", SAMPLES, 210, "Edge 530", ActivitySourceOrigin.ATTACHED, null);
		Activity inside = newBikeActivity(athlete, "June", "Zwift");
		addPowerRecords(inside, SAMPLES, 200);
		newStoredSource(inside, "power", SAMPLES, 210, "Edge 530", ActivitySourceOrigin.ATTACHED, null);

		assertThat(trend(athlete, "power", "trainer", "pedals", "bike").points())
			.extracting(AgreementTrendService.AgreementTrendPoint::activityId)
			.containsExactly(inside.getId());
	}

	@Test
	void dropoutExclusionPassthrough() {
		User athlete = newAthlete("agreement-trend-dropout@example.cc");
		// Primary (trainer) has a confirmed 2s dropout at t=4,5 - the pedals side reads >100W
		// there, corroborating it as a glitch rather than coasting.
		Activity activity = newBikeActivity(athlete, "Ride", "Zwift");
		addRecordsWith(activity, SAMPLES, (record, t) -> record.setPower(t == 4 || t == 5 ? 0 : 200 + t));
		newStoredSource(activity, "power", SAMPLES, 210, "Edge 530", ActivitySourceOrigin.ATTACHED, null);

		AgreementTrendService.AgreementTrendPoint excluded = trend(athlete, "power", "trainer", "pedals", "bike")
			.points().get(0);
		assertThat(excluded.dropoutSecondsA()).isEqualTo(2);
		assertThat(excluded.dropoutSecondsB()).isZero();
		// excludeDropouts defaults to true - the dropout seconds are out of the statistics.
		assertThat(excluded.overlapSeconds()).isEqualTo(SAMPLES - 2);
		assertThat(excluded.bias()).isEqualTo(10.0);

		AgreementTrendService.AgreementTrendPoint included = agreementTrendService
			.computeAgreementTrend(athlete.getId(), "power", "trainer", "pedals", "bike", LocalDate.of(2026, 6, 1),
					LocalDate.of(2026, 6, 30), false, false)
			.points().get(0);
		// Still detected and reported, but now inside the statistics (a=0 there skews bias).
		assertThat(included.dropoutSecondsA()).isEqualTo(2);
		assertThat(included.overlapSeconds()).isEqualTo(SAMPLES);
		assertThat(included.bias()).isGreaterThan(15.0);
	}

	@Test
	void restEndpointReturnsTheTrend() throws Exception {
		User athlete = newAthlete("agreement-trend-rest-200@example.cc");
		Activity activity = newBikeActivity(athlete, "Ride", "Zwift");
		addPowerRecords(activity, SAMPLES, 200);
		newStoredSource(activity, "power", SAMPLES, 210, "Edge 530", ActivitySourceOrigin.ATTACHED, null);

		mockMvc.perform(get("/v1/athletes/" + athlete.getId() + "/streams/agreement")
				.param("field", "power").param("device_a", "trainer").param("device_b", "pedals")
				.param("sport", "bike")
				// The endpoint defaults to a trailing-84-day window from today - the fixture is in
				// June, so pin the range explicitly.
				.param("from_date", "2026-06-01").param("to_date", "2026-06-30")
				.header("Authorization", "Bearer " + tokenFor(athlete)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.field").value("power"))
			.andExpect(jsonPath("$.device_a").value("trainer"))
			.andExpect(jsonPath("$.sport").value("bike"))
			.andExpect(jsonPath("$.points.length()").value(1))
			.andExpect(jsonPath("$.points[0].activity_id").value(activity.getId()))
			.andExpect(jsonPath("$.points[0].date").value("2026-06-03"))
			.andExpect(jsonPath("$.points[0].bias").value(10.0))
			.andExpect(jsonPath("$.skipped.no_device_a").value(0));
	}

	@Test
	void restEndpointRejectsABadCombination() throws Exception {
		User athlete = newAthlete("agreement-trend-rest-400@example.cc");

		// field=power without sport is a 400, not an empty trend.
		mockMvc.perform(get("/v1/athletes/" + athlete.getId() + "/streams/agreement")
				.param("field", "power").param("device_a", "trainer").param("device_b", "pedals")
				.header("Authorization", "Bearer " + tokenFor(athlete)))
			.andExpect(status().isBadRequest());
	}

}
