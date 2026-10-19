package com.cadence.api.activities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cadence.api.activities.dto.ActivitySectionsResponse;
import com.cadence.api.activities.dto.ActivitySplitsResponse;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.common.error.ForbiddenException;
import com.cadence.api.common.error.UnprocessableEntityException;
import com.cadence.api.common.error.ValidationException;
import com.cadence.api.courses.CourseSections;
import com.cadence.api.security.AuthContext;
import com.cadence.api.security.AuthContextHolder;
import com.cadence.api.support.IntegrationTest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.DoubleFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** GET /v1/activities/{id}/sections and POST .../recompute-elevation. Mirrors the Python
 * backend's activities/tests/test_sections.py. */
class ActivitySectionsControllerIntegrationTest extends IntegrationTest {

	@Autowired
	private ActivitySectionsController controller;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private ActivityRepository activityRepository;
	@Autowired
	private RecordRepository recordRepository;
	@Autowired
	private ActivityElevationRepository elevationRepository;

	@AfterEach
	void clearAuthContext() {
		AuthContextHolder.clear();
	}

	private User newAthlete(String email) {
		User user = new User();
		user.setEmail(email);
		user.setName("Sections Athlete");
		user.setPassword("irrelevant-for-this-test");
		return userRepository.save(user);
	}

	private void authAs(String id) {
		AuthContextHolder.set(AuthContext.self(id, Set.of("activities:read", "activities:write"), AuthContext.CredentialKind.OAUTH2));
	}

	/** Flat for 1 km, a 600 m climb at 5%, then flat again. */
	private static double hill(double km) {
		if (km <= 1.0) return 50.0;
		if (km <= 1.6) return 50.0 + (km - 1.0) * 50.0;
		return 80.0;
	}

	private Activity activity(User athlete, Sport sport, DoubleFunction<Double> altitude) {
		Activity activity = new Activity();
		activity.setAthlete(athlete);
		activity.setSport(sport);
		activity.setName("Sections " + sport);
		activity.setStartDate(Instant.parse("2026-02-01T08:00:00Z"));
		activityRepository.save(activity);
		List<Record> records = new ArrayList<>();
		for (int i = 0; i < 1000; i++) {
			Record r = new Record();
			r.setId(new RecordId(activity.getId(), activity.getStartDate().plusSeconds(i)));
			r.setActivity(activity);
			r.setT(i);
			r.setDistanceKm(i * 0.003);
			r.setAltitude(altitude.apply(i * 0.003));
			records.add(r);
		}
		recordRepository.saveAll(records);
		return activity;
	}

	private static List<String> types(ActivitySectionsResponse response) {
		return response.sections().stream().map(CourseSections.Section::type).toList();
	}

	@Test
	void firstReadBuildsTheSeriesAndFindsTheClimb() {
		User athlete = newAthlete("actsections-first@example.cc");
		Activity run = activity(athlete, Sport.RUN, ActivitySectionsControllerIntegrationTest::hill);
		authAs(athlete.getId());
		assertThat(elevationRepository.findById(run.getId())).isEmpty();

		ActivitySectionsResponse response = controller.getSections(run.getId(), Map.of());

		assertThat(response.object()).isEqualTo("activity_sections");
		assertThat(response.activityId()).isEqualTo(run.getId());
		assertThat(response.elevationQuality()).isEqualTo("ok");
		assertThat(types(response)).containsExactly("flat", "steep_climb", "flat");
		assertThat(response.climbs()).hasSize(1);
		assertThat(response.elevationProfile().get(0)).containsExactly(0.0, 50.0);
		assertThat(response.elevationProfile()).hasSizeLessThanOrEqualTo(200);
		ActivityElevation stored = elevationRepository.findById(run.getId()).orElseThrow();
		assertThat(stored.getElevationQuality()).isEqualTo("ok");
		assertThat(stored.getElevationSeries()).hasSize(300);
	}

	@Test
	void previewOverridesApplyAndInvalidOnesAreRejected() {
		User athlete = newAthlete("actsections-preview@example.cc");
		Activity run = activity(athlete, Sport.RUN, ActivitySectionsControllerIntegrationTest::hill);
		authAs(athlete.getId());

		assertThat(types(controller.getSections(run.getId(), Map.of("steep_min_pct", "6"))))
				.containsExactly("flat", "climb", "flat");
		assertThatThrownBy(() -> controller.getSections(run.getId(), Map.of("fit_tolerance_m", "0.1")))
				.isInstanceOf(ValidationException.class);
		assertThatThrownBy(() -> controller.getSections(run.getId(), Map.of("fit_tolerance_m", "abc")))
				.isInstanceOf(ValidationException.class);
	}

	@Test
	void noAltitudeIsMissing() {
		User athlete = newAthlete("actsections-missing@example.cc");
		Activity ride = activity(athlete, Sport.BIKE, km -> null);
		authAs(athlete.getId());

		ActivitySectionsResponse response = controller.getSections(ride.getId(), Map.of());
		assertThat(response.elevationQuality()).isEqualTo("missing");
		assertThat(response.sections()).isEmpty();
		assertThat(response.elevationProfile()).isEmpty();
	}

	@Test
	void otherSportsAreUnprocessable() {
		User athlete = newAthlete("actsections-swim@example.cc");
		Activity swim = new Activity();
		swim.setAthlete(athlete);
		swim.setSport(Sport.SWIM);
		swim.setName("Swim");
		swim.setStartDate(Instant.parse("2026-02-01T08:00:00Z"));
		activityRepository.save(swim);
		authAs(athlete.getId());

		assertThatThrownBy(() -> controller.getSections(swim.getId(), Map.of()))
				.isInstanceOf(UnprocessableEntityException.class);
	}

	@Test
	void recomputeRebuildsFromTheRecordsAndNeedsWriteAccess() {
		User athlete = newAthlete("actsections-recompute@example.cc");
		User outsider = newAthlete("actsections-outsider@example.cc");
		Activity run = activity(athlete, Sport.RUN, ActivitySectionsControllerIntegrationTest::hill);
		authAs(athlete.getId());
		controller.getSections(run.getId(), Map.of());
		ActivityElevation stored = elevationRepository.findById(run.getId()).orElseThrow();
		stored.setElevationQuality("missing");
		stored.setElevationSeries(new ArrayList<>());
		elevationRepository.save(stored);
		assertThat(controller.getSections(run.getId(), Map.of()).sections()).isEmpty();

		ActivitySectionsResponse rebuilt = controller.recomputeElevation(run.getId());
		assertThat(rebuilt.elevationQuality()).isEqualTo("ok");
		assertThat(rebuilt.sections()).hasSize(3);

		authAs(outsider.getId());
		assertThatThrownBy(() -> controller.getSections(run.getId(), Map.of())).isInstanceOf(ForbiddenException.class);
		assertThatThrownBy(() -> controller.recomputeElevation(run.getId())).isInstanceOf(ForbiddenException.class);
	}

	@Test
	void splitsDefaultToLapsModeAndRejectAnUnknownMode() {
		User athlete = newAthlete("actsplits-defaultmode@example.cc");
		Activity run = activity(athlete, Sport.RUN, ActivitySectionsControllerIntegrationTest::hill);
		authAs(athlete.getId());

		ActivitySplitsResponse defaulted = controller.getSplits(run.getId(), "laps");
		assertThat(defaulted.hasLaps()).isFalse();

		assertThatThrownBy(() -> controller.getSplits(run.getId(), "weird")).isInstanceOf(ValidationException.class);
	}

	@Test
	void splitsRequireReadAccess() {
		User athlete = newAthlete("actsplits-outsider2@example.cc");
		User outsider = newAthlete("actsplits-outsider2-out@example.cc");
		Activity run = activity(athlete, Sport.RUN, ActivitySectionsControllerIntegrationTest::hill);
		authAs(outsider.getId());

		assertThatThrownBy(() -> controller.getSplits(run.getId(), "laps")).isInstanceOf(ForbiddenException.class);
	}
}
