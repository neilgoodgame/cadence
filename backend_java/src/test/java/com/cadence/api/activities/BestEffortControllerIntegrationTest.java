package com.cadence.api.activities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.cadence.api.athletes.BestEffortController;
import com.cadence.api.athletes.dto.BestEffortListResponse;
import com.cadence.api.athletes.dto.BestEffortResponse;
import com.cadence.api.athletes.dto.RecentTopEffortResponse;
import com.cadence.api.athletes.dto.RecentTopEffortsListResponse;
import com.cadence.api.common.RecomputeLockRegistry;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.common.error.ConflictException;
import com.cadence.api.common.error.ForbiddenException;
import com.cadence.api.security.AuthContext;
import com.cadence.api.security.AuthContextHolder;
import com.cadence.api.support.IntegrationTest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

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

	@Autowired
	private RecomputeLockRegistry lockRegistry;

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

	private BestEffort makeEffort(User athlete, BestEffortKind kind, Sport sport, String window, double value, long daysAgo) {
		Activity activity = new Activity();
		activity.setAthlete(athlete);
		activity.setSport(sport);
		activity.setName("Activity -" + daysAgo + "d");
		activity.setStartDate(Instant.now().minusSeconds(daysAgo * 86400));
		activity = activityRepository.save(activity);

		BestEffort effort = new BestEffort();
		effort.setAthlete(athlete);
		effort.setKind(kind);
		effort.setWindow(window);
		effort.setValue(value);
		effort.setUnit("w");
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

	@Test
	void recomputeRejectsASecondConcurrentRunForTheSameAthlete() {
		User athlete = newAthlete("recompute-lock@example.cc", 10);
		AuthContextHolder.set(AuthContext.self(athlete.getId(), Set.of("activities:read", "activities:write"), AuthContext.CredentialKind.OAUTH2));

		// Simulates a first recompute already in flight - the controller acquires this same
		// lock synchronously, before the actual (async) work even starts, so this doesn't need
		// a real background task to be genuinely racing to prove the rejection.
		assertThat(lockRegistry.tryAcquire("best-efforts", athlete.getId())).isTrue();
		try {
			assertThatThrownBy(() -> bestEffortController.recompute(athlete.getId(), null))
					.isInstanceOf(ConflictException.class);
		}
		finally {
			lockRegistry.release("best-efforts", athlete.getId());
		}
	}

	@Test
	void recomputeSucceedsOnceThePreviousLockIsReleased() {
		User athlete = newAthlete("recompute-lock-released@example.cc", 10);
		AuthContextHolder.set(AuthContext.self(athlete.getId(), Set.of("activities:read", "activities:write"), AuthContext.CredentialKind.OAUTH2));

		SseEmitter emitter = bestEffortController.recompute(athlete.getId(), null);

		assertThat(emitter).isNotNull();
	}

	@Test
	void activityBestEffortRanksRejectsAnOutsider() {
		User athlete = newAthlete("recent-top-athlete@example.cc", 10);
		User outsider = newAthlete("recent-top-outsider@example.cc", 10);
		AuthContextHolder.set(AuthContext.self(outsider.getId(), Set.of("activities:read"), AuthContext.CredentialKind.OAUTH2));

		assertThatThrownBy(() -> bestEffortController.activityBestEffortRanks(athlete.getId(), ""))
				.isInstanceOf(ForbiddenException.class);
	}

	@Test
	void activityBestEffortRanksReturnsNoDataWithNoActivityIds() {
		User athlete = newAthlete("recent-top-empty@example.cc", 10);
		AuthContextHolder.set(AuthContext.self(athlete.getId(), Set.of("activities:read"), AuthContext.CredentialKind.OAUTH2));

		RecentTopEffortsListResponse response = bestEffortController.activityBestEffortRanks(athlete.getId(), "");

		assertThat(response.data()).isEmpty();
	}

	@Test
	void activityBestEffortRanksReturnsARequestedActivitysRanksPerPeriod() {
		User athlete = newAthlete("recent-top-basic@example.cc", 10);
		AuthContextHolder.set(AuthContext.self(athlete.getId(), Set.of("activities:read"), AuthContext.CredentialKind.OAUTH2));
		BestEffort recent = makeEffort(athlete, BestEffortKind.RUNNING_POWER, Sport.RUN, "5min", 342, 2);

		RecentTopEffortsListResponse response = bestEffortController.activityBestEffortRanks(athlete.getId(), recent.getActivity().getId());

		assertThat(response.data()).hasSize(1);
		RecentTopEffortResponse entry = response.data().get(0);
		assertThat(entry.activityId()).isEqualTo(recent.getActivity().getId());
		assertThat(entry.sport()).isEqualTo(Sport.RUN);
		assertThat(entry.kind()).isEqualTo(BestEffortKind.RUNNING_POWER);
		assertThat(entry.window()).isEqualTo("5min");
		assertThat(entry.value()).isEqualTo(342);
		// The sole effort for this window - rank 1 in every period it's old enough to belong to.
		assertThat(entry.ranks()).containsEntry("16w", 1).containsEntry("1y", 1).containsEntry("all", 1);
	}

	@Test
	void activityBestEffortRanksExcludesAnEntryWithNoTop3RankInAnyPeriod() {
		User athlete = newAthlete("recent-top-notop3@example.cc", 10);
		AuthContextHolder.set(AuthContext.self(athlete.getId(), Set.of("activities:read"), AuthContext.CredentialKind.OAUTH2));
		// Four efforts in the same window, all requested - the 4th-best never ranks <= 3
		// anywhere, even though its id was explicitly included in the request.
		BestEffort a = makeEffort(athlete, BestEffortKind.CYCLING_POWER, Sport.BIKE, "5min", 400, 1);
		BestEffort b = makeEffort(athlete, BestEffortKind.CYCLING_POWER, Sport.BIKE, "5min", 390, 1);
		BestEffort c = makeEffort(athlete, BestEffortKind.CYCLING_POWER, Sport.BIKE, "5min", 380, 1);
		BestEffort d = makeEffort(athlete, BestEffortKind.CYCLING_POWER, Sport.BIKE, "5min", 370, 1);
		String ids = String.join(",", a.getActivity().getId(), b.getActivity().getId(), c.getActivity().getId(), d.getActivity().getId());

		RecentTopEffortsListResponse response = bestEffortController.activityBestEffortRanks(athlete.getId(), ids);

		assertThat(response.data()).extracting(RecentTopEffortResponse::value)
				.containsExactlyInAnyOrder(400.0, 390.0, 380.0);
	}

	@Test
	void activityBestEffortRanksNeverReturnsAnActivityNotIncludedInTheRequestEvenIfItWouldRank1() {
		User athlete = newAthlete("recent-top-notrequested@example.cc", 10);
		AuthContextHolder.set(AuthContext.self(athlete.getId(), Set.of("activities:read"), AuthContext.CredentialKind.OAUTH2));
		makeEffort(athlete, BestEffortKind.RUNNING_POWER, Sport.RUN, "5min", 999, 1);

		RecentTopEffortsListResponse response = bestEffortController.activityBestEffortRanks(athlete.getId(), "");

		assertThat(response.data()).isEmpty();
	}

	@Test
	void activityBestEffortRanksReturnsAnOldActivityIfItsIdIsExplicitlyRequested() {
		// The method has no date logic of its own at all - "recent" is entirely the caller's
		// judgement, made by which ids it asks about, not something it re-derives.
		User athlete = newAthlete("recent-top-old@example.cc", 10);
		AuthContextHolder.set(AuthContext.self(athlete.getId(), Set.of("activities:read"), AuthContext.CredentialKind.OAUTH2));
		BestEffort old = makeEffort(athlete, BestEffortKind.RUNNING_POWER, Sport.RUN, "5min", 999, 400);

		RecentTopEffortsListResponse response = bestEffortController.activityBestEffortRanks(athlete.getId(), old.getActivity().getId());

		assertThat(response.data()).hasSize(1);
	}

	@Test
	void activityBestEffortRanksRanksPaceLowestValueAsRank1() {
		User athlete = newAthlete("recent-top-pace@example.cc", 10);
		AuthContextHolder.set(AuthContext.self(athlete.getId(), Set.of("activities:read"), AuthContext.CredentialKind.OAUTH2));
		BestEffort slow = makeEffort(athlete, BestEffortKind.RUNNING_PACE, Sport.RUN, "10km", 300.0, 1);
		BestEffort mid = makeEffort(athlete, BestEffortKind.RUNNING_PACE, Sport.RUN, "10km", 250.0, 1);
		BestEffort fast = makeEffort(athlete, BestEffortKind.RUNNING_PACE, Sport.RUN, "10km", 200.0, 1);
		String ids = String.join(",", slow.getActivity().getId(), mid.getActivity().getId(), fast.getActivity().getId());

		RecentTopEffortsListResponse response = bestEffortController.activityBestEffortRanks(athlete.getId(), ids);
		var byActivity = response.data().stream()
				.collect(Collectors.toMap(RecentTopEffortResponse::activityId, e -> e.ranks().get("all")));

		assertThat(byActivity.get(fast.getActivity().getId())).isEqualTo(1);
		assertThat(byActivity.get(mid.getActivity().getId())).isEqualTo(2);
		assertThat(byActivity.get(slow.getActivity().getId())).isEqualTo(3);
	}

	@Test
	void activityBestEffortRanksIsNullNotZeroForAPeriodTheActivityDoesNotReachTopNIn() {
		User athlete = newAthlete("recent-top-nullrank@example.cc", 1);
		AuthContextHolder.set(AuthContext.self(athlete.getId(), Set.of("activities:read"), AuthContext.CredentialKind.OAUTH2));
		// An old, faster effort occupies the sole all-time/1y top-1 slot for this window - not
		// itself requested, but still counted when ranking the one that is. A newer, slower one
		// can still rank #1 within the narrower 16w period (nothing else competes there), but
		// must show null for 1y/all, not a real numeric rank.
		makeEffort(athlete, BestEffortKind.CYCLING_POWER, Sport.BIKE, "20min", 400, 300);
		BestEffort recent = makeEffort(athlete, BestEffortKind.CYCLING_POWER, Sport.BIKE, "20min", 350, 1);

		RecentTopEffortsListResponse response = bestEffortController.activityBestEffortRanks(athlete.getId(), recent.getActivity().getId());

		assertThat(response.data()).hasSize(1);
		RecentTopEffortResponse entry = response.data().get(0);
		assertThat(entry.activityId()).isEqualTo(recent.getActivity().getId());
		assertThat(entry.ranks()).containsEntry("16w", 1);
		assertThat(entry.ranks().get("1y")).isNull();
		assertThat(entry.ranks().get("all")).isNull();
	}
}
