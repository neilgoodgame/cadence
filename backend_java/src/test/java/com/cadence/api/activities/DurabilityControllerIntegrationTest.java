package com.cadence.api.activities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cadence.api.athletes.BestEffortController;
import com.cadence.api.athletes.dto.DurabilityResponse;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.common.error.ForbiddenException;
import com.cadence.api.security.AuthContext;
import com.cadence.api.security.AuthContextHolder;
import com.cadence.api.support.IntegrationTest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** GET /v1/athletes/{id}/durability - period/sport filtering, the rolling-average window,
 * pct_of_fresh, and permissions. Mirrors the Python backend's AthleteDurabilityViewTests;
 * activities/ActivityDurability rows are created directly with their fields already set (the
 * computation itself is covered by ActivityDecouplingServiceIntegrationTest). */
class DurabilityControllerIntegrationTest extends IntegrationTest {

	@Autowired
	private BestEffortController bestEffortController;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private ActivityRepository activityRepository;
	@Autowired
	private ActivityDurabilityRepository durabilityRepository;

	@AfterEach
	void clearAuthContext() {
		AuthContextHolder.clear();
	}

	private User newAthlete(String email) {
		User user = new User();
		user.setEmail(email);
		user.setName("Durability Athlete");
		user.setPassword("irrelevant-for-this-test");
		return userRepository.save(user);
	}

	private void authAs(String id) {
		AuthContextHolder.set(AuthContext.self(id, Set.of("activities:read"), AuthContext.CredentialKind.OAUTH2));
	}

	private Activity qualifiedActivity(User athlete, Sport sport, long daysAgo, double decouplingPct, boolean hot, String name) {
		Activity activity = new Activity();
		activity.setAthlete(athlete);
		activity.setSport(sport);
		activity.setName(name);
		activity.setStartDate(Instant.now().minusSeconds(daysAgo * 86400));
		activity.setMovingTime(4200);
		activity.setDecouplingQualified(true);
		activity.setDecouplingPct(decouplingPct);
		activity.setEfFirst(1.5);
		activity.setEfSecond(1.4);
		activity.setSteadySeconds(3600);
		activity.setDecouplingHot(hot);
		activity.setDecouplingAvgTemp(hot ? 28.0 : 18.0);
		activity.setDecouplingHalves(List.of(
				java.util.Map.of("start_s", 0, "end_s", 1799, "power", 200, "hr", 128, "ef", 1.5),
				java.util.Map.of("start_s", 1800, "end_s", 3599, "power", 200, "hr", 131, "ef", 1.4)));
		return activityRepository.save(activity);
	}

	@Test
	void outsiderForbidden() {
		User athlete = newAthlete("durability-athlete@example.cc");
		User outsider = newAthlete("durability-outsider@example.cc");
		authAs(outsider.getId());

		assertThatThrownBy(() -> bestEffortController.getDurability(athlete.getId(), "all", "16w"))
				.isInstanceOf(ForbiddenException.class);
	}

	@Test
	void sportFilterTranslatesBikeToRideAndExcludesRun() {
		User athlete = newAthlete("durability-sport@example.cc");
		qualifiedActivity(athlete, Sport.BIKE, 5, 4.0, false, "Ride");
		qualifiedActivity(athlete, Sport.RUN, 5, 6.0, false, "Run");
		authAs(athlete.getId());

		DurabilityResponse body = bestEffortController.getDurability(athlete.getId(), "ride", "all");

		assertThat(body.sessions()).hasSize(1);
		assertThat(body.sessions().get(0).sport()).isEqualTo("ride");
		assertThat(body.rolling().ride()).isNotNull();
		assertThat(body.rolling().run()).isNull();
	}

	@Test
	void sportAllCombinesBoth() {
		User athlete = newAthlete("durability-all@example.cc");
		qualifiedActivity(athlete, Sport.BIKE, 5, 4.0, false, "Ride");
		qualifiedActivity(athlete, Sport.RUN, 5, 6.0, false, "Run");
		authAs(athlete.getId());

		DurabilityResponse body = bestEffortController.getDurability(athlete.getId(), "all", "all");

		assertThat(body.sessions()).hasSize(2);
		assertThat(body.rolling().ride()).isNotNull();
		assertThat(body.rolling().run()).isNotNull();
	}

	@Test
	void periodFilterExcludesOlderSessions() {
		User athlete = newAthlete("durability-period@example.cc");
		qualifiedActivity(athlete, Sport.BIKE, 10, 4.0, false, "Recent");
		qualifiedActivity(athlete, Sport.BIKE, 200, 5.0, false, "Old");
		authAs(athlete.getId());

		DurabilityResponse fourWeeks = bestEffortController.getDurability(athlete.getId(), "ride", "4w");
		assertThat(fourWeeks.sessions()).extracting(DurabilityResponse.Session::name).containsExactly("Recent");

		DurabilityResponse all = bestEffortController.getDurability(athlete.getId(), "ride", "all");
		assertThat(all.sessions()).extracting(DurabilityResponse.Session::name).containsExactlyInAnyOrder("Recent", "Old");
	}

	@Test
	void rollingAverageIsATrailing28DayMean() {
		User athlete = newAthlete("durability-rolling@example.cc");
		qualifiedActivity(athlete, Sport.BIKE, 50, 10.0, false, "A");
		qualifiedActivity(athlete, Sport.BIKE, 40, 6.0, false, "B");
		qualifiedActivity(athlete, Sport.BIKE, 2, 2.0, false, "C");
		authAs(athlete.getId());

		DurabilityResponse body = bestEffortController.getDurability(athlete.getId(), "ride", "all");
		List<DurabilityResponse.Rolling.Point> rolling = body.rolling().ride();

		assertThat(rolling).hasSize(3);
		assertThat(rolling.get(0).decouplingPct()).isEqualTo(10.0);
		assertThat(rolling.get(1).decouplingPct()).isEqualTo(8.0);
		assertThat(rolling.get(2).decouplingPct()).isEqualTo(2.0);
	}

	@Test
	void summaryLast28AndPrev28() {
		User athlete = newAthlete("durability-summary@example.cc");
		qualifiedActivity(athlete, Sport.BIKE, 10, 4.0, false, "Recent");
		qualifiedActivity(athlete, Sport.BIKE, 40, 8.0, false, "Prev");
		authAs(athlete.getId());

		DurabilityResponse body = bestEffortController.getDurability(athlete.getId(), "ride", "all");

		assertThat(body.summary().count()).isEqualTo(2);
		assertThat(body.summary().last28Avg()).isEqualTo(4.0);
		assertThat(body.summary().prev28Avg()).isEqualTo(8.0);
	}

	@Test
	void summaryCoolAvgExcludesHotSessions() {
		User athlete = newAthlete("durability-cool@example.cc");
		qualifiedActivity(athlete, Sport.BIKE, 5, 4.0, false, "Cool");
		qualifiedActivity(athlete, Sport.BIKE, 6, 10.0, true, "Hot");
		authAs(athlete.getId());

		DurabilityResponse body = bestEffortController.getDurability(athlete.getId(), "ride", "all");

		assertThat(body.summary().last28Avg()).isEqualTo(7.0);
		assertThat(body.summary().last28CoolAvg()).isEqualTo(4.0);
	}

	@Test
	void pctOfFresh() {
		User athlete = newAthlete("durability-pct@example.cc");
		Activity activity = qualifiedActivity(athlete, Sport.BIKE, 5, 4.0, false, "Ride");
		saveDurability(activity, athlete, 0, 1200, 250);
		saveDurability(activity, athlete, 2000, 1200, 220);
		authAs(athlete.getId());

		DurabilityResponse body = bestEffortController.getDurability(athlete.getId(), "ride", "all");
		List<DurabilityResponse.Sections.Cell> cells = body.durability().ride().cells();
		var freshCell = cells.stream().filter(c -> c.threshold() == 0 && c.windowS() == 1200).findFirst().orElseThrow();
		var tiredCell = cells.stream().filter(c -> c.threshold() == 2000 && c.windowS() == 1200).findFirst().orElseThrow();

		assertThat(freshCell.power()).isEqualTo(250);
		assertThat(freshCell.pctOfFresh()).isEqualTo(100);
		assertThat(tiredCell.power()).isEqualTo(220);
		assertThat(tiredCell.pctOfFresh()).isEqualTo(Math.round(220f / 250 * 100));
	}

	@Test
	void durabilityCellPicksTheBestAcrossMultipleActivities() {
		User athlete = newAthlete("durability-best@example.cc");
		Activity lower = qualifiedActivity(athlete, Sport.BIKE, 10, 4.0, false, "Lower");
		Activity higher = qualifiedActivity(athlete, Sport.BIKE, 5, 4.0, false, "Higher");
		saveDurability(lower, athlete, 1000, 300, 200);
		saveDurability(higher, athlete, 1000, 300, 240);
		authAs(athlete.getId());

		DurabilityResponse body = bestEffortController.getDurability(athlete.getId(), "ride", "all");
		var cell = body.durability().ride().cells().stream()
				.filter(c -> c.threshold() == 1000 && c.windowS() == 300).findFirst().orElseThrow();

		assertThat(cell.power()).isEqualTo(240);
		assertThat(cell.activityName()).isEqualTo("Higher");
	}

	private void saveDurability(Activity activity, User athlete, int threshold, int windowS, int power) {
		ActivityDurability d = new ActivityDurability();
		d.setActivity(activity);
		d.setAthlete(athlete);
		d.setSport(Sport.BIKE);
		d.setBasis("kj");
		d.setThreshold(threshold);
		d.setWindowS(windowS);
		d.setPower(power);
		d.setStartOffsetS(0);
		d.setActivityDate(activity.getStartDate().atZone(java.time.ZoneOffset.UTC).toLocalDate());
		durabilityRepository.save(d);
	}
}
