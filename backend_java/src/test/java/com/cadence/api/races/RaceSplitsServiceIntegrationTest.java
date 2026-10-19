package com.cadence.api.races;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.cadence.api.activities.Activity;
import com.cadence.api.activities.Lap;
import com.cadence.api.activities.LapRepository;
import com.cadence.api.activities.Record;
import com.cadence.api.activities.RecordId;
import com.cadence.api.activities.RecordRepository;
import com.cadence.api.activities.dto.CustomSplitStepRequest;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.common.error.ValidationException;
import com.cadence.api.courses.Course;
import com.cadence.api.courses.CourseRepository;
import com.cadence.api.courses.CourseSections;
import com.cadence.api.courses.CourseSource;
import com.cadence.api.races.dto.SplitsResponse;
import com.cadence.api.support.IntegrationTest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Mirrors the Python backend's races/tests/test_splits.py - laps, 1km and 5km fixed-distance
 * splits, has_laps fallback, and the goal-vs-average reference pace. */
class RaceSplitsServiceIntegrationTest extends IntegrationTest {

	@Autowired
	private RaceSplitsService raceSplitsService;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private com.cadence.api.activities.ActivityRepository activityRepository;
	@Autowired
	private RaceRepository raceRepository;
	@Autowired
	private LapRepository lapRepository;
	@Autowired
	private RecordRepository recordRepository;
	@Autowired
	private CourseRepository courseRepository;

	private User newAthlete(String email) {
		User user = new User();
		user.setEmail(email);
		user.setName("Athlete");
		user.setPassword("irrelevant-for-this-test");
		return userRepository.save(user);
	}

	private Activity newActivity(User athlete, Instant startDate, double distanceKm, int movingTime) {
		Activity activity = new Activity();
		activity.setAthlete(athlete);
		activity.setSport(Sport.RUN);
		activity.setName("Race activity");
		activity.setStartDate(startDate);
		activity.setDistanceKm(distanceKm);
		activity.setMovingTime(movingTime);
		return activityRepository.save(activity);
	}

	private void addConstantPaceRecords(Activity activity, double distanceKm, double paceSPerKm, int stepS) {
		addConstantPaceRecords(activity, distanceKm, paceSPerKm, stepS, null, null);
	}

	private void addConstantPaceRecords(
			Activity activity, double distanceKm, double paceSPerKm, int stepS, Integer power, Double altitudePerKm) {
		int totalS = (int) (distanceKm * paceSPerKm);
		Instant start = activity.getStartDate();
		for (int t = 0; t <= totalS; t += stepS) {
			double km = t / paceSPerKm;
			Record record = new Record();
			record.setId(new RecordId(activity.getId(), start.plusSeconds(t)));
			record.setActivity(activity);
			record.setT(t);
			record.setDistanceKm(Math.round(km * 10000) / 10000.0);
			record.setHeartrate(140);
			record.setPower(power);
			record.setAltitude(altitudePerKm != null ? km * altitudePerKm : null);
			recordRepository.save(record);
		}
	}

	@Test
	void multipleLapsReportHasLapsTrue() {
		User athlete = newAthlete("splits-laps-multi@example.cc");
		Activity activity = newActivity(athlete, Instant.parse("2026-10-12T08:00:00Z"), 10.0, 3000);
		Race race = new Race();
		race.setAthlete(athlete);
		race.setName("10k");
		race.setDate(java.time.LocalDate.of(2026, 10, 12));
		race.setActivity(activity);
		race.setDistanceKm(10.0);
		race = raceRepository.save(race);

		Lap lap1 = new Lap();
		lap1.setActivity(activity);
		lap1.setIndex(1);
		lap1.setDuration(300);
		lap1.setDistanceKm(1.0);
		lap1.setAvgHr(140);
		lap1.setAvgPower(255);
		lapRepository.save(lap1);

		Lap lap2 = new Lap();
		lap2.setActivity(activity);
		lap2.setIndex(2);
		lap2.setDuration(300);
		lap2.setDistanceKm(1.0);
		lap2.setAvgHr(142);
		lapRepository.save(lap2);

		SplitsResponse result = raceSplitsService.getSplits(race, "laps");

		assertThat(result.hasLaps()).isTrue();
		assertThat(result.splits()).hasSize(2);
		assertThat(result.splits().get(0).timeS()).isEqualTo(300);
		assertThat(result.splits().get(1).startKm()).isEqualTo(1.0);
		assertThat(result.splits().get(0).avgPower()).isEqualTo(255);
		assertThat(result.splits().get(1).avgPower()).isNull();
		// No altitude on this activity -> no grade -> GAP falls back to the raw pace for delta.
		assertThat(result.splits().get(0).gapPaceSPerKm()).isNull();
		assertThat(result.splits().get(0).deltaVsRefSPerKm()).isEqualTo(0.0);
	}

	@Test
	void lapGradeComesFromTheActivitysOwnAltitude() {
		User athlete = newAthlete("splits-lap-grade@example.cc");
		Activity activity = newActivity(athlete, Instant.parse("2026-10-12T08:00:00Z"), 2.0, 600);
		// A steady +1% grade (10m climbed per km) throughout - laps have no altitude of their
		// own, so this is computed from the activity's records, using each lap's cumulative
		// distance range as the window.
		addConstantPaceRecords(activity, 2.0, 300, 10, null, 10.0);
		Race race = new Race();
		race.setAthlete(athlete);
		race.setName("2k");
		race.setDate(java.time.LocalDate.of(2026, 10, 12));
		race.setActivity(activity);
		race = raceRepository.save(race);

		Lap lap1 = new Lap();
		lap1.setActivity(activity);
		lap1.setIndex(1);
		lap1.setDuration(300);
		lap1.setDistanceKm(1.0);
		lapRepository.save(lap1);

		Lap lap2 = new Lap();
		lap2.setActivity(activity);
		lap2.setIndex(2);
		lap2.setDuration(300);
		lap2.setDistanceKm(1.0);
		lapRepository.save(lap2);

		SplitsResponse result = raceSplitsService.getSplits(race, "laps");

		assertThat(result.splits().get(0).avgGradePct()).isCloseTo(1.0, within(0.1));
		assertThat(result.splits().get(1).avgGradePct()).isCloseTo(1.0, within(0.1));
	}

	@Test
	void singleLapReportsHasLapsFalse() {
		User athlete = newAthlete("splits-laps-single@example.cc");
		Activity activity = newActivity(athlete, Instant.parse("2026-10-12T08:00:00Z"), 10.0, 3000);
		Race race = new Race();
		race.setAthlete(athlete);
		race.setName("10k");
		race.setDate(java.time.LocalDate.of(2026, 10, 12));
		race.setActivity(activity);
		race = raceRepository.save(race);

		Lap lap = new Lap();
		lap.setActivity(activity);
		lap.setIndex(1);
		lap.setDuration(3000);
		lap.setDistanceKm(10.0);
		lapRepository.save(lap);

		assertThat(raceSplitsService.getSplits(race, "laps").hasLaps()).isFalse();
	}

	@Test
	void noLinkedActivityReturnsEmptySplits() {
		User athlete = newAthlete("splits-no-activity@example.cc");
		Race race = new Race();
		race.setAthlete(athlete);
		race.setName("No activity race");
		race.setDate(java.time.LocalDate.of(2026, 10, 12));
		race = raceRepository.save(race);

		SplitsResponse result = raceSplitsService.getSplits(race, "1");

		assertThat(result.splits()).isEmpty();
		assertThat(result.hasLaps()).isFalse();
	}

	@Test
	void oneKmSplitsCoverTheWholeDistanceWithAPartialLastSplit() {
		User athlete = newAthlete("splits-1km@example.cc");
		Activity activity = newActivity(athlete, Instant.parse("2026-10-12T08:00:00Z"), 5.5, 1650);
		// Steady 5:00/km, 200W, +2% grade (20m climbed per km) throughout.
		addConstantPaceRecords(activity, 5.5, 300, 10, 200, 20.0);
		Race race = new Race();
		race.setAthlete(athlete);
		race.setName("5.5k time trial");
		race.setDate(java.time.LocalDate.of(2026, 10, 12));
		race.setActivity(activity);
		race.setDistanceKm(5.5);
		race.setGoalTime(27 * 60 + 30); // exactly 300s/km goal pace
		race = raceRepository.save(race);

		SplitsResponse result = raceSplitsService.getSplits(race, "1");

		assertThat(result.splits()).hasSize(6); // 5 full km + one 0.5km partial
		SplitsResponse.Split last = result.splits().get(result.splits().size() - 1);
		assertThat(last.endKm() - last.startKm()).isCloseTo(0.5, within(0.02));
		assertThat(last.label()).isEqualTo("Finish");
		assertThat(result.ref()).isEqualTo("goal");
		assertThat(result.splits().get(0).paceSPerKm()).isCloseTo(300.0, within(2.0));
		assertThat(result.splits().get(0).avgPower()).isEqualTo(200);
		assertThat(result.splits().get(0).avgGradePct()).isCloseTo(2.0, within(0.1));
	}

	@Test
	void gapPaceAdjustsForGradeAndDrivesTheDelta() {
		User athlete = newAthlete("splits-gap@example.cc");
		Activity activity = newActivity(athlete, Instant.parse("2026-10-12T08:00:00Z"), 5.5, 1650);
		// +2% grade the whole way - GAP should credit the extra climbing effort with a faster
		// flat-equivalent pace than the raw 300s/km, and the delta vs the (flat) goal pace
		// should follow GAP, not the raw pace.
		addConstantPaceRecords(activity, 5.5, 300, 10, null, 20.0);
		Race race = new Race();
		race.setAthlete(athlete);
		race.setName("5.5k time trial");
		race.setDate(java.time.LocalDate.of(2026, 10, 12));
		race.setActivity(activity);
		race.setDistanceKm(5.5);
		race.setGoalTime(27 * 60 + 30); // exactly 300s/km goal pace
		race = raceRepository.save(race);

		SplitsResponse result = raceSplitsService.getSplits(race, "1");

		SplitsResponse.Split first = result.splits().get(0);
		assertThat(first.gapPaceSPerKm()).isLessThan(first.paceSPerKm());
		assertThat(first.deltaVsRefSPerKm()).isCloseTo(first.gapPaceSPerKm() - 300, within(0.1));
		assertThat(Math.abs(first.deltaVsRefSPerKm())).isGreaterThan(5.0);
	}

	@Test
	void powerDeltaFallsBackToActivityAveragePowerWhenNoTargetSet() {
		User athlete = newAthlete("splits-power-average@example.cc");
		Activity activity = newActivity(athlete, Instant.parse("2026-10-12T08:00:00Z"), 5.5, 1650);
		activity.setAvgPower(250);
		activity = activityRepository.save(activity);
		addConstantPaceRecords(activity, 5.5, 300, 10, 260, null);
		Race race = new Race();
		race.setAthlete(athlete);
		race.setName("5.5k time trial");
		race.setDate(java.time.LocalDate.of(2026, 10, 12));
		race.setActivity(activity);
		race.setDistanceKm(5.5);
		race = raceRepository.save(race);

		SplitsResponse result = raceSplitsService.getSplits(race, "1");

		assertThat(result.refPower()).isEqualTo("average");
		assertThat(result.refPowerW()).isEqualTo(250);
		assertThat(result.splits().get(0).avgPower()).isEqualTo(260);
		assertThat(result.splits().get(0).deltaPowerW()).isEqualTo(10);
	}

	@Test
	void powerDeltaUsesTargetPowerWhenSet() {
		User athlete = newAthlete("splits-power-target@example.cc");
		Activity activity = newActivity(athlete, Instant.parse("2026-10-12T08:00:00Z"), 5.5, 1650);
		activity.setAvgPower(250);
		activity = activityRepository.save(activity);
		addConstantPaceRecords(activity, 5.5, 300, 10, 260, null);
		Race race = new Race();
		race.setAthlete(athlete);
		race.setName("5.5k time trial");
		race.setDate(java.time.LocalDate.of(2026, 10, 12));
		race.setActivity(activity);
		race.setDistanceKm(5.5);
		race.setTargetPower(240);
		race = raceRepository.save(race);

		SplitsResponse result = raceSplitsService.getSplits(race, "1");

		assertThat(result.refPower()).isEqualTo("target");
		assertThat(result.refPowerW()).isEqualTo(240);
		assertThat(result.splits().get(0).deltaPowerW()).isEqualTo(20);
	}

	@Test
	void powerDeltaIsNullWithNoPowerDataAtAll() {
		User athlete = newAthlete("splits-power-none@example.cc");
		Activity activity = newActivity(athlete, Instant.parse("2026-10-12T08:00:00Z"), 5.5, 1650);
		addConstantPaceRecords(activity, 5.5, 300, 10);
		Race race = new Race();
		race.setAthlete(athlete);
		race.setName("5.5k time trial");
		race.setDate(java.time.LocalDate.of(2026, 10, 12));
		race.setActivity(activity);
		race.setDistanceKm(5.5);
		race = raceRepository.save(race);

		SplitsResponse result = raceSplitsService.getSplits(race, "1");

		assertThat(result.refPower()).isEqualTo("average");
		assertThat(result.refPowerW()).isNull();
		assertThat(result.splits().get(0).deltaPowerW()).isNull();
	}

	/** A synthetic elevation profile from (lengthM, gradePct) pieces, starting at 100m - same
	 * shape as CourseSectionsTest's own ramp helper, duplicated here to avoid a cross-package
	 * test dependency. */
	private record RampProfile(List<Double> distancesKm, List<Double> elevationsM) {}

	private RampProfile ramp(double[][] segments) {
		List<Double> distances = new ArrayList<>(List.of(0.0));
		List<Double> elevations = new ArrayList<>(List.of(100.0));
		for (double[] segment : segments) {
			double lengthM = segment[0];
			double gradePct = segment[1];
			for (int i = 0; i < (int) (lengthM / 10.0); i++) {
				distances.add(distances.get(distances.size() - 1) + 10.0 / 1000.0);
				elevations.add(elevations.get(elevations.size() - 1) + 10.0 * gradePct / 100.0);
			}
		}
		return new RampProfile(distances, elevations);
	}

	@Test
	void elevationSplitsFollowCourseSections() {
		User athlete = newAthlete("splits-elevation@example.cc");

		// 3km flat, 1km @ 3%, 3km flat - proven by CourseSectionsTest's testFlatClimbFlat to
		// produce exactly [flat, climb, flat] sections under the default settings.
		RampProfile profile = ramp(new double[][] {{3000, 0.0}, {1000, 3.0}, {3000, 0.0}});
		CourseSections.ElevationSeries series = CourseSections.buildElevationSeries(profile.distancesKm(), profile.elevationsM());
		Course course = new Course();
		course.setAthlete(athlete);
		course.setName("Elevation course");
		course.setSource(CourseSource.GPX);
		course.setDistanceKm(7.0);
		course.setElevationQuality(series.elevationQuality());
		course.setElevationSeries(series.series());
		course = courseRepository.save(course);

		Activity activity = newActivity(athlete, Instant.parse("2026-10-12T08:00:00Z"), 7.0, 2100);
		// A fine (1s) step keeps the recorded distance grid fine enough that the climb
		// section's boundaries (not round numbers) land on a sampled point, same as real GPS
		// data - a coarse step would bias the pace of a split whose boundary falls between
		// two samples.
		addConstantPaceRecords(activity, 7.0, 300, 1);
		Race race = new Race();
		race.setAthlete(athlete);
		race.setName("Elevation race");
		race.setDate(java.time.LocalDate.of(2026, 10, 12));
		race.setActivity(activity);
		race.setCourse(course);
		race.setDistanceKm(7.0);
		race.setGoalTime(35 * 60); // exactly 300s/km over 7km
		race = raceRepository.save(race);

		SplitsResponse result = raceSplitsService.getSplits(race, "elevation");

		assertThat(result.splits()).extracting(SplitsResponse.Split::sectionType).containsExactly("flat", "climb", "flat");
		SplitsResponse.Split climb = result.splits().get(1);
		assertThat(climb.startKm()).isCloseTo(3.0, within(0.1));
		assertThat(climb.endKm()).isCloseTo(4.0, within(0.1));
		assertThat(climb.paceSPerKm()).isCloseTo(300.0, within(3.0));
		assertThat(climb.gainM()).isGreaterThan(0);
		assertThat(result.splits().get(0).label()).isNull();
	}

	@Test
	void elevationSplitsWithNoCourseAreEmpty() {
		User athlete = newAthlete("splits-elevation-no-course@example.cc");
		Activity activity = newActivity(athlete, Instant.parse("2026-10-13T08:00:00Z"), 7.0, 2100);
		Race race = new Race();
		race.setAthlete(athlete);
		race.setName("No course");
		race.setDate(java.time.LocalDate.of(2026, 10, 13));
		race.setActivity(activity);
		race = raceRepository.save(race);

		assertThat(raceSplitsService.getSplits(race, "elevation").splits()).isEmpty();
	}

	@Test
	void elevationSplitsWithCourseMissingElevationAreEmpty() {
		User athlete = newAthlete("splits-elevation-no-sections@example.cc");
		Activity activity = newActivity(athlete, Instant.parse("2026-10-14T08:00:00Z"), 7.0, 2100);
		Course course = new Course();
		course.setAthlete(athlete);
		course.setName("No elevation");
		course.setSource(CourseSource.GPX);
		course = courseRepository.save(course);
		Race race = new Race();
		race.setAthlete(athlete);
		race.setName("Flat course race");
		race.setDate(java.time.LocalDate.of(2026, 10, 14));
		race.setActivity(activity);
		race.setCourse(course);
		race = raceRepository.save(race);

		assertThat(raceSplitsService.getSplits(race, "elevation").splits()).isEmpty();
	}

	@Test
	void fiveKmSplitsProduceTwoSplits() {
		User athlete = newAthlete("splits-5km@example.cc");
		Activity activity = newActivity(athlete, Instant.parse("2026-10-12T08:00:00Z"), 5.5, 1650);
		addConstantPaceRecords(activity, 5.5, 300, 10);
		Race race = new Race();
		race.setAthlete(athlete);
		race.setName("5.5k time trial");
		race.setDate(java.time.LocalDate.of(2026, 10, 12));
		race.setActivity(activity);
		race.setDistanceKm(5.5);
		race = raceRepository.save(race);

		SplitsResponse result = raceSplitsService.getSplits(race, "5");

		assertThat(result.splits()).hasSize(2);
		assertThat(result.splits().get(0).endKm()).isCloseTo(5.0, within(0.02));
	}

	@Test
	void customSplitsGetTheRacesOwnGoalDelta() {
		User athlete = newAthlete("splits-custom-goal@example.cc");
		Activity activity = newActivity(athlete, Instant.parse("2026-10-12T08:00:00Z"), 5.5, 1650);
		addConstantPaceRecords(activity, 5.5, 300, 10, 260, null);
		Race race = new Race();
		race.setAthlete(athlete);
		race.setName("5.5k time trial");
		race.setDate(java.time.LocalDate.of(2026, 10, 12));
		race.setActivity(activity);
		race.setDistanceKm(5.5);
		race.setGoalTime(27 * 60 + 30); // exactly 300s/km goal pace
		race = raceRepository.save(race);

		SplitsResponse result = raceSplitsService.customSplits(
				race, List.of(new CustomSplitStepRequest("distance", null, 1.0, null)));

		assertThat(result.ref()).isEqualTo("goal");
		assertThat(result.splits().get(0).deltaVsRefSPerKm()).isCloseTo(0.0, within(0.5));
	}

	@Test
	void customSplitsWithNoActivityReturnEmptySplitsWithoutValidatingSteps() {
		User athlete = newAthlete("splits-custom-noactivity@example.cc");
		Race race = new Race();
		race.setAthlete(athlete);
		race.setName("No activity");
		race.setDate(java.time.LocalDate.of(2026, 10, 12));
		race = raceRepository.save(race);

		SplitsResponse result = raceSplitsService.customSplits(race, null);

		assertThat(result.splits()).isEmpty();
	}

	@Test
	void customSplitsRejectsMalformedSteps() {
		User athlete = newAthlete("splits-custom-badsteps@example.cc");
		Activity activity = newActivity(athlete, Instant.parse("2026-10-12T08:00:00Z"), 5.5, 1650);
		addConstantPaceRecords(activity, 5.5, 300, 10);
		Race race = new Race();
		race.setAthlete(athlete);
		race.setName("5.5k time trial");
		race.setDate(java.time.LocalDate.of(2026, 10, 12));
		race.setActivity(activity);
		Race savedRace = raceRepository.save(race);

		assertThatThrownBy(() -> raceSplitsService.customSplits(
				savedRace, List.of(new CustomSplitStepRequest("time", null, null, null))))
				.isInstanceOf(ValidationException.class);
	}
}
