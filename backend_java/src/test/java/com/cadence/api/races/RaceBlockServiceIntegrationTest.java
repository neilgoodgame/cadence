package com.cadence.api.races;

import static org.assertj.core.api.Assertions.assertThat;

import com.cadence.api.activities.Activity;
import com.cadence.api.activities.ActivityRepository;
import com.cadence.api.activities.ActivityTag;
import com.cadence.api.activities.ActivityTagRepository;
import com.cadence.api.activities.Tag;
import com.cadence.api.activities.TagRepository;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.races.dto.RaceBlockResponse;
import com.cadence.api.support.IntegrationTest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Mirrors the Python backend's races/tests/test_blocks.py - block range/truncation, the
 * parent-A lookup, comparison alignment, Riegel prediction and key sessions. */
class RaceBlockServiceIntegrationTest extends IntegrationTest {

	@Autowired
	private RaceBlockService raceBlockService;
	@Autowired
	private RaceRepository raceRepository;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private ActivityRepository activityRepository;
	@Autowired
	private TagRepository tagRepository;
	@Autowired
	private ActivityTagRepository activityTagRepository;

	private User newAthlete(String email) {
		User user = new User();
		user.setEmail(email);
		user.setName("Athlete");
		user.setPassword("irrelevant-for-this-test");
		return userRepository.save(user);
	}

	private Race newRace(User athlete, String name, LocalDate date, RacePriority priority) {
		Race race = new Race();
		race.setAthlete(athlete);
		race.setName(name);
		race.setDate(date);
		race.setPriority(priority);
		return raceRepository.save(race);
	}

	private static LocalDate monday(LocalDate d) {
		return d.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
	}

	@Test
	void defaultBlockIsTheFullPreferenceLength() {
		User athlete = newAthlete("block-default@example.cc");
		Race race = newRace(athlete, "Marathon", LocalDate.of(2026, 10, 12), RacePriority.A);

		RaceBlockService.BlockRange block = raceBlockService.aRaceBlockRange(race, 16);

		assertThat(block.weeks()).isEqualTo(16);
		assertThat(block.endWeek()).isEqualTo(monday(race.getDate()));
		assertThat(block.truncatedByRaceId()).isNull();
		assertThat(block.startWeek()).isEqualTo(monday(race.getDate()).minusWeeks(15));
	}

	@Test
	void blockTruncatedByAnEarlierARace() {
		User athlete = newAthlete("block-truncated@example.cc");
		Race earlier = newRace(athlete, "Spring Marathon", LocalDate.of(2026, 3, 1), RacePriority.A);
		Race later = newRace(athlete, "Autumn Marathon", LocalDate.of(2026, 6, 1), RacePriority.A);

		RaceBlockService.BlockRange block = raceBlockService.aRaceBlockRange(later, 16);

		assertThat(block.weeks()).isLessThan(16);
		assertThat(block.truncatedByRaceId()).isEqualTo(earlier.getId());
		assertThat(block.startWeek()).isEqualTo(monday(earlier.getDate()).plusWeeks(1));
	}

	@Test
	void bRaceInsideABlocksWindowFindsParent() {
		User athlete = newAthlete("block-parent-found@example.cc");
		Race aRace = newRace(athlete, "Goal Marathon", LocalDate.of(2026, 10, 12), RacePriority.A);
		Race bRace = newRace(athlete, "Tune-up Half", LocalDate.of(2026, 9, 14), RacePriority.B);

		Race parent = raceBlockService.findParentARace(bRace, 16);

		assertThat(parent).isNotNull();
		assertThat(parent.getId()).isEqualTo(aRace.getId());
	}

	@Test
	void bRaceOutsideAnyBlockHasNoParent() {
		User athlete = newAthlete("block-parent-none@example.cc");
		newRace(athlete, "Goal Marathon", LocalDate.of(2026, 10, 12), RacePriority.A);
		Race standalone = newRace(athlete, "Lone 10k", LocalDate.of(2026, 1, 5), RacePriority.B);

		assertThat(raceBlockService.findParentARace(standalone, 16)).isNull();
	}

	@Test
	void aRaceBlockShapeHasTheRightNumberOfWeeksAndPhases() {
		User athlete = newAthlete("block-shape-a@example.cc");
		Race race = newRace(athlete, "Goal Marathon", LocalDate.of(2026, 10, 12), RacePriority.A);
		race.setSport(Sport.RUN);
		race.setDistanceKm(42.2);
		raceRepository.save(race);

		RaceBlockResponse result = raceBlockService.getRaceBlock(race);

		assertThat(result.weeks()).hasSize(16);
		assertThat(result.phases()).hasSize(4);
		assertThat(result.raceDayForm()).isNotNull();
		assertThat(result.keySessions()).isNotNull();
		assertThat(result.sweatRateSource()).isEqualTo("default");
	}

	@Test
	void bRaceBlockShapeIsAFiveWeekWindow() {
		User athlete = newAthlete("block-shape-b@example.cc");
		Race race = newRace(athlete, "Tune-up Half", LocalDate.of(2026, 9, 14), RacePriority.B);
		race.setSport(Sport.RUN);
		race.setDistanceKm(21.1);
		raceRepository.save(race);

		RaceBlockResponse result = raceBlockService.getRaceBlock(race);

		assertThat(result.weeks()).hasSize(5);
		assertThat(result.phases()).isEmpty();
	}

	@Test
	void comparisonFindsMostRecentSameDistanceARace() {
		User athlete = newAthlete("block-comparison@example.cc");
		newRace(athlete, "Spring Marathon", LocalDate.of(2025, 10, 1), RacePriority.A);
		Race older = raceRepository.findByAthleteIdOrderByDateAsc(athlete.getId()).get(0);
		older.setSport(Sport.RUN);
		older.setDistanceKm(42.2);
		raceRepository.save(older);

		Race newerCandidate = newRace(athlete, "Summer Marathon", LocalDate.of(2026, 4, 1), RacePriority.A);
		newerCandidate.setSport(Sport.RUN);
		newerCandidate.setDistanceKm(42.0);
		raceRepository.save(newerCandidate);

		Race thisRace = newRace(athlete, "Autumn Marathon", LocalDate.of(2026, 10, 12), RacePriority.A);
		thisRace.setSport(Sport.RUN);
		thisRace.setDistanceKm(42.2);
		raceRepository.save(thisRace);

		RaceBlockResponse result = raceBlockService.getRaceBlock(thisRace);

		assertThat(result.comparison()).isNotNull();
		assertThat(result.comparison().raceId()).isEqualTo(newerCandidate.getId());
	}

	@Test
	void riegelPredictionPicksTheFastestSource() {
		User athlete = newAthlete("block-riegel@example.cc");
		Race aRace = newRace(athlete, "Goal Marathon", LocalDate.of(2026, 10, 12), RacePriority.A);
		aRace.setSport(Sport.RUN);
		aRace.setDistanceKm(42.2);
		raceRepository.save(aRace);

		Race slowSource = newRace(athlete, "Slow 10k", LocalDate.of(2026, 9, 1), RacePriority.C);
		slowSource.setSport(Sport.RUN);
		slowSource.setDistanceKm(10.0);
		slowSource.setResultTime(45 * 60);
		raceRepository.save(slowSource);

		Race fastSource = newRace(athlete, "Fast Half", LocalDate.of(2026, 9, 15), RacePriority.B);
		fastSource.setSport(Sport.RUN);
		fastSource.setDistanceKm(21.1);
		fastSource.setResultTime(85 * 60);
		raceRepository.save(fastSource);

		RaceBlockResponse result = raceBlockService.getRaceBlock(aRace);

		assertThat(result.prediction()).isNotNull();
		assertThat(result.prediction().fromRaceId()).isEqualTo(fastSource.getId());
	}

	/** The athlete's race_key_session_cap preference no longer trims key_sessions.items - the
	 * frontend paginates the full candidate list client-side instead, using that preference as
	 * its page size. items.size() must always equal totalCandidates(). */
	@Test
	void keySessionsReturnsEveryCandidateRegardlessOfCap() {
		User athlete = newAthlete("block-key-sessions@example.cc");
		Race race = newRace(athlete, "Goal Marathon", LocalDate.of(2026, 10, 12), RacePriority.A);
		race.setSport(Sport.RUN);
		race.setDistanceKm(42.2);
		raceRepository.save(race);
		athlete.setRaceKeySessionCap(6); // lower than the candidate count this setup produces
		userRepository.save(athlete);

		for (int i = 0; i < 6; i++) {
			Activity run = new Activity();
			run.setAthlete(athlete);
			run.setSport(Sport.RUN);
			run.setName("Long run " + i);
			run.setStartDate(Instant.parse("2026-09-0" + (i + 2) + "T07:00:00Z"));
			run.setDistanceKm(25.0 + i);
			run.setMovingTime(9000);
			activityRepository.save(run);
		}

		RaceBlockResponse result = raceBlockService.getRaceBlock(race);

		assertThat(result.keySessions().totalCandidates()).isGreaterThanOrEqualTo(4);
		assertThat(result.keySessions().items()).hasSize(result.keySessions().totalCandidates());
	}

	/** Each key session item carries its own sport, independent of the race's sport - the
	 * frontend's sport filter chips key off this field. */
	@Test
	void keySessionItemsCarryTheirOwnSportForFrontendFiltering() {
		User athlete = newAthlete("block-key-sessions-sport@example.cc");
		Race race = newRace(athlete, "Goal Marathon", LocalDate.of(2026, 10, 12), RacePriority.A);
		race.setSport(Sport.RUN);
		race.setDistanceKm(42.2);
		raceRepository.save(race);

		Tag tag = new Tag();
		tag.setAthlete(athlete);
		tag.setName("Key session");
		tag = tagRepository.save(tag);

		Activity bikeTagged = new Activity();
		bikeTagged.setAthlete(athlete);
		bikeTagged.setSport(Sport.BIKE);
		bikeTagged.setName("Tagged ride");
		bikeTagged.setStartDate(Instant.parse("2026-09-05T07:00:00Z"));
		bikeTagged.setDistanceKm(40.0);
		bikeTagged.setMovingTime(5400);
		bikeTagged = activityRepository.save(bikeTagged);
		ActivityTag bikeLink = new ActivityTag();
		bikeLink.setActivity(bikeTagged);
		bikeLink.setTag(tag);
		activityTagRepository.save(bikeLink);

		Activity runTagged = new Activity();
		runTagged.setAthlete(athlete);
		runTagged.setSport(Sport.RUN);
		runTagged.setName("Tagged run");
		runTagged.setStartDate(Instant.parse("2026-09-06T07:00:00Z"));
		runTagged.setDistanceKm(12.0);
		runTagged.setMovingTime(3000);
		runTagged = activityRepository.save(runTagged);
		ActivityTag runLink = new ActivityTag();
		runLink.setActivity(runTagged);
		runLink.setTag(tag);
		activityTagRepository.save(runLink);

		RaceBlockResponse result = raceBlockService.getRaceBlock(race);
		Map<String, RaceBlockResponse.KeySessionItem> byActivityId = result.keySessions().items().stream()
				.collect(Collectors.toMap(RaceBlockResponse.KeySessionItem::activityId, i -> i));

		assertThat(byActivityId.get(bikeTagged.getId()).sport()).isEqualTo(Sport.BIKE);
		assertThat(byActivityId.get(runTagged.getId()).sport()).isEqualTo(Sport.RUN);
	}

	@Test
	void weekTssIsSplitBySport() {
		User athlete = newAthlete("block-sport-split@example.cc");
		Race race = newRace(athlete, "Spring Marathon", LocalDate.of(2026, 3, 1), RacePriority.A);
		race.setSport(Sport.RUN);
		raceRepository.save(race);

		saveActivity(athlete, Sport.RUN, 80, "2026-02-17T07:00:00Z");
		saveActivity(athlete, Sport.BIKE, 120, "2026-02-18T07:00:00Z");
		saveActivity(athlete, Sport.SWIM, 30, "2026-02-19T07:00:00Z");

		RaceBlockResponse result = raceBlockService.getRaceBlock(race);
		RaceBlockResponse.WeekAggregate week = result.weeks().stream()
				.filter(w -> w.weekStart().equals(LocalDate.of(2026, 2, 16))).findFirst().orElseThrow();

		assertThat(week.tss()).isEqualTo(230);
		assertThat(week.runTss()).isEqualTo(80);
		assertThat(week.rideTss()).isEqualTo(120);
	}

	private void saveActivity(User athlete, Sport sport, int tss, String startDate) {
		Activity activity = new Activity();
		activity.setAthlete(athlete);
		activity.setSport(sport);
		activity.setName("Session");
		activity.setStartDate(Instant.parse(startDate));
		activity.setDistanceKm(10.0);
		activity.setMovingTime(3600);
		activity.setTss(tss);
		activityRepository.save(activity);
	}
}
