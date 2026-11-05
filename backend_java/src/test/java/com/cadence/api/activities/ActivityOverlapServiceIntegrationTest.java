package com.cadence.api.activities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.cadence.api.activities.dto.OverlapPairResponse;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.common.paging.CursorPage;
import com.cadence.api.support.IntegrationTest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Issue #498's overlap-detection backbone - see ActivityOverlapRepository's Javadoc. */
class ActivityOverlapServiceIntegrationTest extends IntegrationTest {

	@Autowired
	private ActivityOverlapService overlapService;

	@Autowired
	private ActivityRepository activityRepository;

	@Autowired
	private UserRepository userRepository;

	private User newUser(String email) {
		User user = new User();
		user.setEmail(email);
		user.setName("Test User " + email);
		user.setPassword("irrelevant-for-this-test");
		return userRepository.save(user);
	}

	private Activity newActivity(User athlete, Sport sport, String device, Instant startDate, int elapsedSeconds) {
		Activity activity = new Activity();
		activity.setAthlete(athlete);
		activity.setSport(sport);
		activity.setName(sport + " on " + startDate);
		activity.setDevice(device);
		activity.setStartDate(startDate);
		activity.setElapsedSeconds(elapsedSeconds);
		activity.setMovingTime(elapsedSeconds);
		return activityRepository.save(activity);
	}

	@Test
	void fullOverlapBetweenTwoDevicesIsDetected() {
		User athlete = newUser("full-overlap@example.com");
		Instant start = Instant.parse("2026-06-01T18:00:00Z");
		Activity zwift = newActivity(athlete, Sport.BIKE, "Zwift", start, 3600);
		Activity garmin = newActivity(athlete, Sport.BIKE, "Garmin Edge 830", start.plusSeconds(5), 3600);

		CursorPage<OverlapPairResponse> page = overlapService.findOverlaps(
				athlete.getId(), null, null, false, null, null, null, null, null, null, List.of(), null, null, false,
				null, 50);

		assertThat(page.data()).hasSize(1);
		OverlapPairResponse pair = page.data().get(0);
		// Which side lands as anchor vs other is arbitrary (lower id) when both directions
		// qualify equally, as they do here - assert pair membership, not a specific side.
		assertThat(List.of(pair.anchor().id(), pair.other().id()))
				.containsExactlyInAnyOrder(zwift.getId(), garmin.getId());
		assertThat(pair.overlapSeconds()).isEqualTo(3595);
		assertThat(pair.overlapPct()).isCloseTo(99.86, within(0.1));
		assertThat(pair.startDeltaSeconds()).isEqualTo(5);
		assertThat(pair.status()).isEqualTo("unresolved");
	}

	@Test
	void partialOverlapReportsTheOverlappingSliceOnly() {
		User athlete = newUser("partial-overlap@example.com");
		Instant start = Instant.parse("2026-06-01T18:00:00Z");
		// b starts 1800s (half of a's 3600s duration) into a, so they share exactly 1800s.
		newActivity(athlete, Sport.BIKE, "Zwift", start, 3600);
		newActivity(athlete, Sport.BIKE, "Garmin", start.plusSeconds(1800), 3600);

		CursorPage<OverlapPairResponse> page = overlapService.findOverlaps(
				athlete.getId(), null, null, false, null, null, null, null, null, null, List.of(), null, null, false,
				null, 50);

		assertThat(page.data()).hasSize(1);
		assertThat(page.data().get(0).overlapSeconds()).isEqualTo(1800);
		assertThat(page.data().get(0).overlapPct()).isCloseTo(50.0, within(0.1));
	}

	@Test
	void nonOverlappingActivitiesAreNotReturned() {
		User athlete = newUser("no-overlap@example.com");
		Instant start = Instant.parse("2026-06-01T18:00:00Z");
		newActivity(athlete, Sport.BIKE, "Zwift", start, 3600);
		newActivity(athlete, Sport.BIKE, "Garmin", start.plusSeconds(7200), 3600);

		CursorPage<OverlapPairResponse> page = overlapService.findOverlaps(
				athlete.getId(), null, null, false, null, null, null, null, null, null, List.of(), null, null, false,
				null, 50);

		assertThat(page.data()).isEmpty();
	}

	@Test
	void minOverlapPctBoundaryExcludesAPairBelowItAndIncludesOneAtOrAboveIt() {
		User athlete = newUser("min-overlap-pct@example.com");
		Instant start = Instant.parse("2026-06-01T18:00:00Z");
		// 400s overlap out of a 1000s shorter side -> exactly 40%.
		newActivity(athlete, Sport.BIKE, "Zwift", start, 1000);
		newActivity(athlete, Sport.BIKE, "Garmin", start.plusSeconds(600), 1000);

		CursorPage<OverlapPairResponse> excluded = overlapService.findOverlaps(
				athlete.getId(), null, null, false, null, null, null, null, null, null, List.of(), 50.0, null, false,
				null, 50);
		assertThat(excluded.data()).isEmpty();

		CursorPage<OverlapPairResponse> included = overlapService.findOverlaps(
				athlete.getId(), null, null, false, null, null, null, null, null, null, List.of(), 40.0, null, false,
				null, 50);
		assertThat(included.data()).hasSize(1);
	}

	@Test
	void deviceAndOtherDeviceFiltersMatchCaseInsensitivelyByPrefix() {
		User athlete = newUser("device-filter@example.com");
		Instant start = Instant.parse("2026-06-01T18:00:00Z");
		newActivity(athlete, Sport.BIKE, "Zwift X", start, 3600);
		newActivity(athlete, Sport.BIKE, "Garmin Edge 830", start.plusSeconds(5), 3600);

		CursorPage<OverlapPairResponse> matched = overlapService.findOverlaps(
				athlete.getId(), null, null, false, "zwift", "garmin", null, null, null, null, List.of(), null, null,
				false, null, 50);
		assertThat(matched.data()).hasSize(1);

		CursorPage<OverlapPairResponse> unmatched = overlapService.findOverlaps(
				athlete.getId(), null, null, false, "peloton", null, null, null, null, null, List.of(), null, null,
				false, null, 50);
		assertThat(unmatched.data()).isEmpty();
	}

	@Test
	void fromAndToFilterOnTheAnchorsStartDate() {
		User athlete = newUser("date-range@example.com");
		newActivity(athlete, Sport.BIKE, "Zwift", Instant.parse("2026-01-15T18:00:00Z"), 3600);
		newActivity(athlete, Sport.BIKE, "Garmin", Instant.parse("2026-01-15T18:00:05Z"), 3600);
		newActivity(athlete, Sport.BIKE, "Zwift", Instant.parse("2026-03-15T18:00:00Z"), 3600);
		newActivity(athlete, Sport.BIKE, "Garmin", Instant.parse("2026-03-15T18:00:05Z"), 3600);

		CursorPage<OverlapPairResponse> page = overlapService.findOverlaps(
				athlete.getId(), null, null, false, null, null, null, LocalDate.of(2026, 3, 1),
				LocalDate.of(2026, 3, 31), null, List.of(), null, null, false, null, 50);

		assertThat(page.data()).hasSize(1);
		// from/to only constrains the anchor side (see the repository's own doc comment) -
		// either March activity may land as anchor vs other, so check both ends up on March 15
		// rather than assuming a specific side.
		OverlapPairResponse pair = page.data().get(0);
		assertThat(List.of(pair.anchor().startDate(), pair.other().startDate()))
				.containsExactlyInAnyOrder(Instant.parse("2026-03-15T18:00:00Z"), Instant.parse("2026-03-15T18:00:05Z"));
	}

	@Test
	void includeResolvedExcludesAnAlreadyLinkedDuplicateByDefault() {
		User athlete = newUser("include-resolved@example.com");
		Instant start = Instant.parse("2026-06-01T18:00:00Z");
		Activity zwift = newActivity(athlete, Sport.BIKE, "Zwift", start, 3600);
		Activity garmin = newActivity(athlete, Sport.BIKE, "Garmin", start.plusSeconds(5), 3600);
		garmin.setPrimaryActivity(zwift);
		activityRepository.save(garmin);

		CursorPage<OverlapPairResponse> excludedByDefault = overlapService.findOverlaps(
				athlete.getId(), null, null, false, null, null, null, null, null, null, List.of(), null, null, false,
				null, 50);
		assertThat(excludedByDefault.data()).isEmpty();

		CursorPage<OverlapPairResponse> includedExplicitly = overlapService.findOverlaps(
				athlete.getId(), null, null, false, null, null, null, null, null, null, List.of(), null, null, true,
				null, 50);
		assertThat(includedExplicitly.data()).hasSize(1);
		assertThat(includedExplicitly.data().get(0).status()).isEqualTo("duplicate");
	}

	@Test
	void activityIdPinsTheAnchorToThatOneActivity() {
		User athlete = newUser("activity-id@example.com");
		Instant start = Instant.parse("2026-06-01T18:00:00Z");
		Activity zwift = newActivity(athlete, Sport.BIKE, "Zwift", start, 3600);
		Activity garmin = newActivity(athlete, Sport.BIKE, "Garmin", start.plusSeconds(5), 3600);

		CursorPage<OverlapPairResponse> fromGarmin = overlapService.findOverlaps(
				athlete.getId(), garmin.getId(), null, false, null, null, null, null, null, null, List.of(), null,
				null, false, null, 50);

		assertThat(fromGarmin.data()).hasSize(1);
		// Pinned as anchor even though its id sorts after zwift's - unlike the unordered
		// listing mode, activityId always wins the anchor/other assignment.
		assertThat(fromGarmin.data().get(0).anchor().id()).isEqualTo(garmin.getId());
		assertThat(fromGarmin.data().get(0).other().id()).isEqualTo(zwift.getId());
	}

	@Test
	void otherHasFiltersOnWhichSummaryFieldsTheOtherSideHasSingleAndRepeated() {
		User athlete = newUser("other-has@example.com");
		Instant start = Instant.parse("2026-06-01T18:00:00Z");
		newActivity(athlete, Sport.BIKE, "Zwift", start, 3600);
		Activity garmin = newActivity(athlete, Sport.BIKE, "Garmin", start.plusSeconds(5), 3600);
		garmin.setAvgPower(220);
		activityRepository.save(garmin);

		CursorPage<OverlapPairResponse> withPower = overlapService.findOverlaps(
				athlete.getId(), null, null, false, null, null, null, null, null, null, List.of("power"), null, null,
				false, null, 50);
		assertThat(withPower.data()).hasSize(1);

		// Repeated otherHas (power AND heartrate) - garmin has power but no heart rate, so
		// requiring both must exclude the pair.
		CursorPage<OverlapPairResponse> withPowerAndHeartrate = overlapService.findOverlaps(
				athlete.getId(), null, null, false, null, null, null, null, null, null,
				List.of("power", "heartrate"), null, null, false, null, 50);
		assertThat(withPowerAndHeartrate.data()).isEmpty();
	}

	@Test
	void minDurationSecondsIgnoresAShortActivityOnEitherSide() {
		User athlete = newUser("min-duration@example.com");
		Instant start = Instant.parse("2026-06-01T18:00:00Z");
		newActivity(athlete, Sport.BIKE, "Zwift", start, 3600);
		newActivity(athlete, Sport.BIKE, "Garmin", start.plusSeconds(5), 60); // a short warm-up save.

		CursorPage<OverlapPairResponse> withoutFilter = overlapService.findOverlaps(
				athlete.getId(), null, null, false, null, null, null, null, null, null, List.of(), null, null, false,
				null, 50);
		assertThat(withoutFilter.data()).hasSize(1);

		CursorPage<OverlapPairResponse> withFilter = overlapService.findOverlaps(
				athlete.getId(), null, null, false, null, null, null, null, null, 300, List.of(), null, null, false,
				null, 50);
		assertThat(withFilter.data()).isEmpty();
	}

	@Test
	void environmentFilterAppliesToBothSides() {
		User athlete = newUser("environment-filter@example.com");
		Instant start = Instant.parse("2026-06-01T18:00:00Z");
		Activity indoorA = newActivity(athlete, Sport.BIKE, "Zwift", start, 3600);
		indoorA.setEnvironment(Environment.INDOOR);
		activityRepository.save(indoorA);
		Activity outdoorB = newActivity(athlete, Sport.BIKE, "Garmin", start.plusSeconds(5), 3600);
		outdoorB.setEnvironment(Environment.OUTDOOR);
		activityRepository.save(outdoorB);

		CursorPage<OverlapPairResponse> indoorOnly = overlapService.findOverlaps(
				athlete.getId(), null, null, false, null, null, Environment.INDOOR, null, null, null, List.of(), null,
				null, false, null, 50);
		assertThat(indoorOnly.data()).isEmpty();

		CursorPage<OverlapPairResponse> unfiltered = overlapService.findOverlaps(
				athlete.getId(), null, null, false, null, null, null, null, null, null, List.of(), null, null, false,
				null, 50);
		assertThat(unfiltered.data()).hasSize(1);
	}

	@Test
	void neverReturnsAPairAcrossDifferentAthletes() {
		User athleteA = newUser("cross-athlete-a@example.com");
		User athleteB = newUser("cross-athlete-b@example.com");
		Instant start = Instant.parse("2026-06-01T18:00:00Z");
		newActivity(athleteA, Sport.BIKE, "Zwift", start, 3600);
		newActivity(athleteB, Sport.BIKE, "Garmin", start.plusSeconds(5), 3600);

		CursorPage<OverlapPairResponse> page = overlapService.findOverlaps(
				athleteA.getId(), null, null, false, null, null, null, null, null, null, List.of(), null, null, false,
				null, 50);

		assertThat(page.data()).isEmpty();
	}

	@Test
	void sportFilterRequiresBothSidesUnlessCrossSportIsSet() {
		User athlete = newUser("cross-sport@example.com");
		Instant start = Instant.parse("2026-06-01T18:00:00Z");
		newActivity(athlete, Sport.BIKE, "Zwift", start, 3600);
		newActivity(athlete, Sport.RUN, "Garmin", start.plusSeconds(5), 3600);

		CursorPage<OverlapPairResponse> sameSportOnly = overlapService.findOverlaps(
				athlete.getId(), null, Sport.BIKE, false, null, null, null, null, null, null, List.of(), null, null,
				false, null, 50);
		assertThat(sameSportOnly.data()).isEmpty();

		CursorPage<OverlapPairResponse> crossSport = overlapService.findOverlaps(
				athlete.getId(), null, Sport.BIKE, true, null, null, null, null, null, null, List.of(), null, null,
				false, null, 50);
		assertThat(crossSport.data()).hasSize(1);
	}
}
