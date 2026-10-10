package com.cadence.api.activities;

import static org.assertj.core.api.Assertions.assertThat;

import com.cadence.api.athletes.ThresholdField;
import com.cadence.api.athletes.ThresholdHistory;
import com.cadence.api.athletes.ThresholdHistoryRepository;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.support.IntegrationTest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import com.cadence.api.workouts.StepEndType;
import com.cadence.api.workouts.StepKind;
import com.cadence.api.workouts.TargetType;
import com.cadence.api.workouts.Workout;
import com.cadence.api.workouts.WorkoutRepository;
import com.cadence.api.workouts.WorkoutStep;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** ActivityDecouplingService#computeAndPersist end-to-end against real Records and
 * ThresholdHistory - mirrors the Python backend's ComputeDecouplingTests (the pure qualification
 * math itself is covered by DecouplingDurabilityCalculatorTest). */
class ActivityDecouplingServiceIntegrationTest extends IntegrationTest {

	@Autowired
	private ActivityDecouplingService decouplingService;
	@Autowired
	private ActivityRepository activityRepository;
	@Autowired
	private RecordRepository recordRepository;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private ThresholdHistoryRepository thresholdHistoryRepository;
	@Autowired
	private ActivityDurabilityRepository durabilityRepository;
	@Autowired
	private WorkoutRepository workoutRepository;

	private User newAthlete(String email) {
		User user = new User();
		user.setEmail(email);
		user.setName("Decoupling Athlete");
		user.setPassword("irrelevant-for-this-test");
		return userRepository.save(user);
	}

	private Activity newActivity(User athlete, Sport sport, int movingTime) {
		Activity activity = new Activity();
		activity.setAthlete(athlete);
		activity.setSport(sport);
		activity.setName("Session");
		activity.setStartDate(Instant.parse("2026-06-01T07:00:00Z"));
		activity.setMovingTime(movingTime);
		return activityRepository.save(activity);
	}

	private void setThreshold(User athlete, Activity activity, ThresholdField field, int value) {
		ThresholdHistory entry = new ThresholdHistory();
		entry.setAthlete(athlete);
		entry.setField(field);
		entry.setValueNumeric(value);
		entry.setSourceActivity(activity);
		entry.setEffectiveFrom(LocalDate.of(2026, 1, 1));
		entry.setCurrentFrom(LocalDate.of(2026, 1, 1));
		thresholdHistoryRepository.save(entry);
	}

	private void addRecords(Activity activity, int n, Integer power, Integer hr) {
		Instant start = activity.getStartDate();
		for (int t = 0; t < n; t++) {
			Record record = new Record();
			record.setId(new RecordId(activity.getId(), start.plusSeconds(t)));
			record.setActivity(activity);
			record.setT(t);
			record.setPower(power);
			record.setHeartrate(hr);
			recordRepository.save(record);
		}
	}

	@Test
	void nonBikeRunSportGetsSportReasonAndNothingComputed() {
		User athlete = newAthlete("decoupling-sport@example.cc");
		Activity activity = newActivity(athlete, Sport.SWIM, 4000);
		addRecords(activity, 4000, 200, 140);

		decouplingService.computeAndPersist(activity, athlete);

		assertThat(activity.getDecouplingReasons()).containsExactly("sport");
		assertThat(activity.isDecouplingQualified()).isFalse();
		assertThat(activity.getDecouplingPct()).isNull();
	}

	@Test
	void noPowerStreamGetsNoPowerReason() {
		User athlete = newAthlete("decoupling-no-power@example.cc");
		Activity activity = newActivity(athlete, Sport.BIKE, 4000);
		addRecords(activity, 4000, null, 140);

		decouplingService.computeAndPersist(activity, athlete);

		assertThat(activity.getDecouplingReasons()).containsExactly("no_power");
	}

	@Test
	void qualifiedSessionSplitsIntoTwoEqualHalvesAndComputesPct() {
		User athlete = newAthlete("decoupling-qualified@example.cc");
		// The HR profile below deliberately bands at t=600 (end of warmup) and t=2400 (midpoint
		// of a 3600s steady window) - pinned to the design spec's original 10-minute warmup
		// rather than the athlete-configurable default (5 min) so this test's halves stay exactly
		// where the fixture intends regardless of what that default is.
		athlete.setDecouplingWarmupMinutes(10);
		athlete = userRepository.save(athlete);
		Activity activity = newActivity(athlete, Sport.BIKE, 4200);
		setThreshold(athlete, activity, ThresholdField.FTP, 250);

		Instant start = activity.getStartDate();
		for (int t = 0; t < 4200; t++) {
			Integer hr = t < 600 ? 140 : (t < 600 + 1800 ? 128 : 131);
			Record record = new Record();
			record.setId(new RecordId(activity.getId(), start.plusSeconds(t)));
			record.setActivity(activity);
			record.setT(t);
			record.setPower(200);
			record.setHeartrate(hr);
			recordRepository.save(record);
		}

		decouplingService.computeAndPersist(activity, athlete);

		assertThat(activity.isDecouplingQualified()).isTrue();
		assertThat(activity.getDecouplingReasons()).isEmpty();
		assertThat(activity.getEfFirst()).isCloseTo(200.0 / 128, org.assertj.core.api.Assertions.within(0.01));
		assertThat(activity.getEfSecond()).isCloseTo(200.0 / 131, org.assertj.core.api.Assertions.within(0.01));
		assertThat(activity.getDecouplingPct()).isGreaterThan(0);
		assertThat(activity.getDecouplingHalves()).hasSize(2);
	}

	@Test
	void negativeDecouplingIsStoredUnchanged() {
		User athlete = newAthlete("decoupling-negative@example.cc");
		Activity activity = newActivity(athlete, Sport.BIKE, 4200);
		setThreshold(athlete, activity, ThresholdField.FTP, 250);

		Instant start = activity.getStartDate();
		for (int t = 0; t < 4200; t++) {
			Integer hr = t < 2500 ? 140 : 130;
			Record record = new Record();
			record.setId(new RecordId(activity.getId(), start.plusSeconds(t)));
			record.setActivity(activity);
			record.setT(t);
			record.setPower(200);
			record.setHeartrate(hr);
			recordRepository.save(record);
		}

		decouplingService.computeAndPersist(activity, athlete);

		assertThat(activity.isDecouplingQualified()).isTrue();
		assertThat(activity.getDecouplingPct()).isLessThan(0);
	}

	private void addRecordsWithTemps(Activity activity, Double airTemp, Double coreTemp, Double skinTemp) {
		Instant start = activity.getStartDate();
		for (int t = 0; t < 4000; t++) {
			Record record = new Record();
			record.setId(new RecordId(activity.getId(), start.plusSeconds(t)));
			record.setActivity(activity);
			record.setT(t);
			record.setPower(200);
			record.setHeartrate(140);
			record.setAirTemp(airTemp);
			record.setCoreTemp(coreTemp);
			record.setSkinTemp(skinTemp);
			recordRepository.save(record);
		}
	}

	@Test
	void warmFromAirAlone() {
		// Warm is an OR - either signal elevated is enough, and it doesn't require skin data
		// to be present at all.
		User athlete = newAthlete("decoupling-warm-air@example.cc");
		Activity activity = newActivity(athlete, Sport.RUN, 4000);
		setThreshold(athlete, activity, ThresholdField.CRITICAL_RUN_POWER, 250);
		addRecordsWithTemps(activity, 26.0, null, null);

		decouplingService.computeAndPersist(activity, athlete);

		assertThat(activity.isDecouplingWarm()).isTrue();
		assertThat(activity.isDecouplingHot()).isFalse();
		assertThat(activity.getDecouplingAvgTemp()).isCloseTo(26.0, org.assertj.core.api.Assertions.within(0.1));
	}

	@Test
	void warmFromSkinAlone() {
		User athlete = newAthlete("decoupling-warm-skin@example.cc");
		Activity activity = newActivity(athlete, Sport.BIKE, 4000);
		setThreshold(athlete, activity, ThresholdField.FTP, 250);
		addRecordsWithTemps(activity, null, null, 33.0); // at the warm floor but below the hot floor (34)

		decouplingService.computeAndPersist(activity, athlete);

		assertThat(activity.isDecouplingWarm()).isTrue();
		assertThat(activity.isDecouplingHot()).isFalse();
		assertThat(activity.getDecouplingAvgSkin()).isCloseTo(33.0, org.assertj.core.api.Assertions.within(0.1));
	}

	@Test
	void hotFromAirAndSkinBothElevated() {
		User athlete = newAthlete("decoupling-hot@example.cc");
		Activity activity = newActivity(athlete, Sport.BIKE, 4000);
		setThreshold(athlete, activity, ThresholdField.FTP, 250);
		addRecordsWithTemps(activity, 31.0, null, 35.0);

		decouplingService.computeAndPersist(activity, athlete);

		assertThat(activity.isDecouplingWarm()).isTrue();
		assertThat(activity.isDecouplingHot()).isTrue();
	}

	@Test
	void highAirAloneIsHotToo() {
		// Hot is an OR, same as warm - air alone crossing its own (higher) hot floor is enough,
		// even with skin below both the warm (33) and hot (34) skin floors.
		User athlete = newAthlete("decoupling-warm-only-air@example.cc");
		Activity activity = newActivity(athlete, Sport.BIKE, 4000);
		setThreshold(athlete, activity, ThresholdField.FTP, 250);
		addRecordsWithTemps(activity, 31.0, null, 30.0);

		decouplingService.computeAndPersist(activity, athlete);

		assertThat(activity.isDecouplingWarm()).isTrue();
		assertThat(activity.isDecouplingHot()).isTrue();
	}

	@Test
	void highSkinAloneIsHotToo() {
		// Same OR logic from the skin side - air below both the warm (25) and hot (30) air
		// floors doesn't stop skin alone crossing its own hot floor.
		User athlete = newAthlete("decoupling-warm-only-skin@example.cc");
		Activity activity = newActivity(athlete, Sport.BIKE, 4000);
		setThreshold(athlete, activity, ThresholdField.FTP, 250);
		addRecordsWithTemps(activity, 20.0, null, 35.0);

		decouplingService.computeAndPersist(activity, athlete);

		assertThat(activity.isDecouplingWarm()).isTrue();
		assertThat(activity.isDecouplingHot()).isTrue();
	}

	@Test
	void highCoreAloneIsNeitherWarmNorHot() {
		// Core temp no longer gates either flag - a long steady effort drives it up from
		// sustained exertion alone, even on a cool day, so it's informational only
		// (getDecouplingAvgCore). This is the exact real-world false positive that prompted
		// the switch to air/skin: a 3h run, ~16 C air, core drifting to ~38.1 C, skin staying
		// ~31.6 C (below even the 33 C warm-skin floor) since the body was shedding heat into
		// cool air just fine.
		User athlete = newAthlete("decoupling-core-no-skin@example.cc");
		Activity activity = newActivity(athlete, Sport.RUN, 4000);
		setThreshold(athlete, activity, ThresholdField.CRITICAL_RUN_POWER, 250);
		addRecordsWithTemps(activity, 16.2, 38.1, 31.6);

		decouplingService.computeAndPersist(activity, athlete);

		assertThat(activity.isDecouplingWarm()).isFalse();
		assertThat(activity.isDecouplingHot()).isFalse();
		assertThat(activity.getDecouplingAvgCore()).isCloseTo(38.1, org.assertj.core.api.Assertions.within(0.1));
	}

	@Test
	void notWarmBelowAllThresholds() {
		User athlete = newAthlete("decoupling-cool@example.cc");
		Activity activity = newActivity(athlete, Sport.BIKE, 4000);
		setThreshold(athlete, activity, ThresholdField.FTP, 250);
		addRecords(activity, 4000, 200, 140);

		decouplingService.computeAndPersist(activity, athlete);

		assertThat(activity.isDecouplingWarm()).isFalse();
		assertThat(activity.isDecouplingHot()).isFalse();
	}

	@Test
	void athletesOwnConfiguredHeatThresholdsAreUsedNotTheDefaults() {
		// A session that's cool under the design-spec defaults (air 27 C < 30 C hot floor)
		// trips "hot" once the athlete loosens their own hot-air floor to 26 C - confirms
		// applyDecoupling actually reads User.decouplingHotAirTemp/SkinTemp rather than the
		// module-level defaults.
		User athlete = newAthlete("decoupling-configured-heat@example.cc");
		athlete.setDecouplingHotAirTemp(26.0);
		athlete = userRepository.save(athlete);
		Activity activity = newActivity(athlete, Sport.BIKE, 4000);
		setThreshold(athlete, activity, ThresholdField.FTP, 250);
		addRecordsWithTemps(activity, 27.0, null, 35.0);

		decouplingService.computeAndPersist(activity, athlete);

		assertThat(activity.isDecouplingHot()).isTrue();
	}

	@Test
	void reasonsAndChecksStillPopulatedWhenNotQualified() {
		User athlete = newAthlete("decoupling-not-qualified@example.cc");
		Activity activity = newActivity(athlete, Sport.BIKE, 4000);
		setThreshold(athlete, activity, ThresholdField.FTP, 100); // IF = 200/100 = 2.0, fails
		addRecords(activity, 4000, 200, 140);

		decouplingService.computeAndPersist(activity, athlete);

		assertThat(activity.isDecouplingQualified()).isFalse();
		assertThat(activity.getDecouplingReasons()).contains("intensity");
		assertThat(activity.getDecouplingVi()).isNotNull();
		assertThat(activity.getDecouplingIf()).isNotNull();
		assertThat(activity.getSteadySeconds()).isEqualTo(3700); // 4000 - the default 5 min (300s) warmup trim
		assertThat(activity.getDecouplingPct()).isNull();
		assertThat(activity.getDecouplingHalves()).isEmpty();
	}

	@Test
	void matchedWorkoutWarmupStepOverridesTheFlatDefaultWhenOptedIn() {
		User athlete = newAthlete("decoupling-workout-warmup@example.cc");
		athlete.setDecouplingUseWorkoutWarmup(true);
		athlete = userRepository.save(athlete);

		Workout workout = new Workout();
		workout.setCreatedBy(athlete);
		workout.setName("Steady ride with a long warmup");
		workout.setSport(Sport.BIKE);
		workout.setDuration(4000);
		WorkoutStep warmupStep = new WorkoutStep();
		warmupStep.setWorkout(workout);
		warmupStep.setOrder(0);
		warmupStep.setKind(StepKind.WARMUP);
		warmupStep.setEndType(StepEndType.TIME);
		warmupStep.setDuration(900); // 15 min - longer than the flat 5 min default
		warmupStep.setTargetType(TargetType.POWER);
		warmupStep.setTargetLow(50.0);
		warmupStep.setTargetHigh(50.0);
		workout.getSteps().add(warmupStep);
		workout = workoutRepository.saveAndFlush(workout);

		Activity activity = newActivity(athlete, Sport.BIKE, 4000);
		activity.setWorkout(workout);
		activity = activityRepository.save(activity);
		setThreshold(athlete, activity, ThresholdField.FTP, 250);
		addRecords(activity, 4000, 200, 140);

		decouplingService.computeAndPersist(activity, athlete);

		// 4000 - the matched workout's own 900s warmup step, not the flat 300s default.
		assertThat(activity.getSteadySeconds()).isEqualTo(3100);
	}

	@Test
	void matchedWorkoutWithNoWarmupStepFallsBackToTheFlatDefault() {
		User athlete = newAthlete("decoupling-workout-no-warmup@example.cc");
		athlete.setDecouplingUseWorkoutWarmup(true);
		athlete = userRepository.save(athlete);

		Workout workout = new Workout();
		workout.setCreatedBy(athlete);
		workout.setName("Straight into the work, no warmup step");
		workout.setSport(Sport.BIKE);
		workout.setDuration(4000);
		WorkoutStep blockStep = new WorkoutStep();
		blockStep.setWorkout(workout);
		blockStep.setOrder(0);
		blockStep.setKind(StepKind.BLOCK);
		blockStep.setEndType(StepEndType.TIME);
		blockStep.setDuration(4000);
		blockStep.setTargetType(TargetType.POWER);
		blockStep.setTargetLow(100.0);
		blockStep.setTargetHigh(100.0);
		workout.getSteps().add(blockStep);
		workout = workoutRepository.saveAndFlush(workout);

		Activity activity = newActivity(athlete, Sport.BIKE, 4000);
		activity.setWorkout(workout);
		activity = activityRepository.save(activity);
		setThreshold(athlete, activity, ThresholdField.FTP, 250);
		addRecords(activity, 4000, 200, 140);

		decouplingService.computeAndPersist(activity, athlete);

		// No warmup step on the matched workout, so this falls back to the flat 300s default.
		assertThat(activity.getSteadySeconds()).isEqualTo(3700);
	}

	@Test
	void athletesOwnConfiguredThresholdsAreUsedNotTheDefaults() {
		// A session that's a clean pass under the design-spec defaults (IF 0.8 <= 0.85) fails
		// once the athlete tightens their own IF limit to 0.7 - confirms computeAndPersist
		// actually reads User.decouplingIfLimit rather than the calculator's own default.
		User athlete = newAthlete("decoupling-configured@example.cc");
		athlete.setDecouplingIfLimit(0.7);
		athlete = userRepository.save(athlete);
		Activity activity = newActivity(athlete, Sport.BIKE, 4200);
		setThreshold(athlete, activity, ThresholdField.FTP, 250);
		addRecords(activity, 4200, 200, 140);

		decouplingService.computeAndPersist(activity, athlete);

		assertThat(activity.isDecouplingQualified()).isFalse();
		assertThat(activity.getDecouplingReasons()).contains("intensity");
	}

	@Test
	void computeAndPersistWritesDurabilityRowsIdempotently() {
		User athlete = newAthlete("decoupling-durability@example.cc");
		Activity activity = newActivity(athlete, Sport.BIKE, 4000);
		addRecords(activity, 4000, 1000, 140);

		decouplingService.computeAndPersist(activity, athlete);
		int firstCount = durabilityRepository.findByActivityIdOrderByThresholdAscWindowSAsc(activity.getId()).size();
		assertThat(firstCount).isGreaterThan(0);

		// Idempotent: recomputing replaces, not duplicates.
		decouplingService.computeAndPersist(activity, athlete);
		int secondCount = durabilityRepository.findByActivityIdOrderByThresholdAscWindowSAsc(activity.getId()).size();
		assertThat(secondCount).isEqualTo(firstCount);
	}
}
