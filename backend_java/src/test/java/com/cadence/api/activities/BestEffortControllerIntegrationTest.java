package com.cadence.api.activities;

import static org.assertj.core.api.Assertions.assertThat;

import com.cadence.api.athletes.BestEffortController;
import com.cadence.api.athletes.dto.BestEffortListResponse;
import com.cadence.api.athletes.dto.BestEffortResponse;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.security.AuthContext;
import com.cadence.api.security.AuthContextHolder;
import com.cadence.api.support.IntegrationTest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Before native "4w"/"16w" periods existed, the frontend faked "16 weeks" by fetching the wider
 * "1y" bucket (already capped to topN there) and narrowing client-side to 112 days - which could
 * drop entries that are genuinely top-N within 112 days but not within the top-N of the full
 * 365-day bucket. period=16w must query that exact window natively instead.
 */
class BestEffortControllerIntegrationTest extends IntegrationTest {

	@Autowired
	private BestEffortController bestEffortController;

	@Autowired
	private BestEffortRepository bestEffortRepository;

	@Autowired
	private BestEffortComputeService bestEffortComputeService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private ActivityRepository activityRepository;

	@AfterEach
	void clearAuthContext() {
		AuthContextHolder.clear();
	}

	private User newAthlete(String email, int topN) {
		User user = new User();
		user.setEmail(email);
		user.setName("16w Athlete");
		user.setPassword("irrelevant-for-this-test");
		user.setBestEffortTopN(topN);
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
		effort.setWindow("1km");
		effort.setValue(value);
		effort.setUnit("sec_per_km");
		effort.setDate(LocalDate.now().minusDays(daysAgo));
		effort.setActivity(activity);
		return bestEffortRepository.save(effort);
	}

	@Test
	void sixteenWeekPeriodQueriesNativeOneTwelveDayWindow() {
		User athlete = newAthlete("java-16w@example.cc", 2);
		// Two very fast efforts outside the 112-day window (but inside 365) - these would
		// dominate a naive "top-2 of the last year, then narrow to 112 days" query down to
		// nothing, since neither survives the narrowing.
		makeEffort(athlete, 150.0, 200);
		makeEffort(athlete, 151.0, 210);
		// Two slower-but-still-notable efforts inside the last 112 days - what "16 weeks"
		// should actually show.
		BestEffort recent1 = makeEffort(athlete, 280.0, 20);
		BestEffort recent2 = makeEffort(athlete, 285.0, 50);

		bestEffortComputeService.trim(athlete.getId(), BestEffortKind.RUNNING_PACE, "1km", true, 2);

		AuthContextHolder.set(AuthContext.self(athlete.getId(), Set.of("activities:read"), AuthContext.CredentialKind.OAUTH2));
		BestEffortListResponse response = bestEffortController.listBestEfforts(athlete.getId(), BestEffortKind.RUNNING_PACE, "16w");

		assertThat(response.data()).extracting(BestEffortResponse::activityId)
				.containsExactlyInAnyOrder(recent1.getActivity().getId(), recent2.getActivity().getId());
	}
}
