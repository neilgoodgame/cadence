package com.cadence.api.activities;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class LapDerivationServiceTest extends IntegrationTest {

	@Autowired
	private LapDerivationService lapDerivationService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private WorkoutRepository workoutRepository;

	@Autowired
	private ActivityRepository activityRepository;

	@Autowired
	private RecordRepository recordRepository;

	@Autowired
	private LapRepository lapRepository;

	private User newAthlete(String email) {
		User user = new User();
		user.setEmail(email);
		user.setName("Test Athlete");
		user.setPassword("irrelevant-for-this-test");
		return userRepository.save(user);
	}

	private Activity newActivity(User athlete, Instant start) {
		Activity activity = new Activity();
		activity.setAthlete(athlete);
		activity.setSport(Sport.BIKE);
		activity.setName("Ride");
		activity.setStartDate(start);
		return activityRepository.save(activity);
	}

	/** warmup 600s@60%, then 5x[work 300s@110%, rest 300s@52.5%] - the real workout this
	 * feature was designed against (see the session's "Gorby" investigation). */
	private Workout newGorbyWorkout(User athlete) {
		Workout workout = new Workout();
		workout.setCreatedBy(athlete);
		workout.setName("The Gorby");
		workout.setSport(Sport.BIKE);

		WorkoutStep warmup = leaf(workout, null, 0, StepKind.WARMUP, 600, 50.0, 70.0);
		WorkoutStep group = new WorkoutStep();
		group.setWorkout(workout);
		group.setOrder(1);
		group.setKind(StepKind.REPEAT);
		group.setRepeat(5);
		WorkoutStep work = leaf(workout, group, 0, StepKind.BLOCK, 300, 110.0, 110.0);
		WorkoutStep rest = leaf(workout, group, 1, StepKind.REC, 300, 50.0, 55.0);
		workout.getSteps().add(warmup);
		workout.getSteps().add(group);
		workout.getSteps().add(work);
		workout.getSteps().add(rest);
		return workoutRepository.saveAndFlush(workout);
	}

	private WorkoutStep leaf(Workout workout, WorkoutStep parent, int order, StepKind kind, int duration,
			double targetLow, double targetHigh) {
		WorkoutStep step = new WorkoutStep();
		step.setWorkout(workout);
		step.setParentStep(parent);
		step.setOrder(order);
		step.setKind(kind);
		step.setEndType(StepEndType.TIME);
		step.setDuration(duration);
		step.setTargetType(TargetType.POWER);
		step.setTargetLow(targetLow);
		step.setTargetHigh(targetHigh);
		return step;
	}

	private static int phasePower(int t) {
		if (t < 600) {
			return 150; // warmup
		}
		int repT = (t - 600) % 600;
		return repT < 300 ? 280 : 140; // work / rest
	}

	private void seedRecords(Activity activity, Instant start, int totalSeconds) {
		for (int t = 0; t < totalSeconds; t++) {
			Record record = new Record();
			record.setId(new RecordId(activity.getId(), start.plusSeconds(t)));
			record.setActivity(activity);
			record.setT(t);
			record.setPower(phasePower(t));
			record.setHeartrate(130 + (t % 20));
			record.setDistanceKm(t * 0.01);
			recordRepository.save(record);
		}
	}

	@Test
	void derives11LapsMatchingTheGorbysRealStructure() {
		User athlete = newAthlete("lap-derivation@example.cc");
		Activity activity = newActivity(athlete, Instant.parse("2026-01-01T06:00:00Z"));
		Workout workout = newGorbyWorkout(athlete);
		// +1: adjacent segments share their boundary sample (it closes one, opens the next), so
		// a clean run through all 11 planned segments needs one more sample than their summed
		// planned duration (3600s).
		seedRecords(activity, activity.getStartDate(), 3601);

		List<Lap> laps = lapDerivationService.deriveLaps(activity, workout);

		assertThat(laps).hasSize(11);
		assertThat(laps).extracting(Lap::getDuration).containsExactly(600, 300, 300, 300, 300, 300, 300, 300, 300, 300, 300);
		assertThat(laps.get(0).getWorkoutStep().getKind()).isEqualTo(StepKind.WARMUP);
		assertThat(laps.get(0).getRepeatIndex()).isNull();
		assertThat(laps.get(1).getWorkoutStep().getKind()).isEqualTo(StepKind.BLOCK);
		assertThat(laps.get(1).getRepeatIndex()).isEqualTo(1);
		assertThat(laps.get(1).getAvgPower()).isCloseTo(280, org.assertj.core.data.Offset.offset(1));
		assertThat(laps.get(2).getWorkoutStep().getKind()).isEqualTo(StepKind.REC);
		assertThat(laps.get(2).getRepeatIndex()).isEqualTo(1);
		assertThat(laps.get(9).getRepeatIndex()).isEqualTo(5);
		// Every "block" lap points at the same WorkoutStep row - the DB only stores one row per
		// template step, not one per repetition.
		Set<Long> blockStepIds = laps.stream().filter(l -> l.getWorkoutStep() != null && l.getWorkoutStep().getKind() == StepKind.BLOCK)
				.map(l -> l.getWorkoutStep().getId()).collect(Collectors.toSet());
		assertThat(blockStepIds).hasSize(1);
	}

	@Test
	void trailingRecordsBeyondThePlanBecomeOneUnlinkedLap() {
		// Matches the real Gorby ride investigated during planning: 12 device laps for an
		// 11-step workout, the extra one a short "stop recording" tail.
		User athlete = newAthlete("lap-derivation-trailing@example.cc");
		Activity activity = newActivity(athlete, Instant.parse("2026-01-02T06:00:00Z"));
		Workout workout = newGorbyWorkout(athlete);
		seedRecords(activity, activity.getStartDate(), 3637); // 3601 to complete the plan + 36s trailing

		List<Lap> laps = lapDerivationService.deriveLaps(activity, workout);

		assertThat(laps).hasSize(12);
		Lap trailing = laps.get(11);
		assertThat(trailing.getWorkoutStep()).isNull();
		assertThat(trailing.getRepeatIndex()).isNull();
		assertThat(trailing.getDuration()).isEqualTo(35); // last record's t (3636) - first (3601)
	}

	@Test
	void activityShorterThanThePlanProducesNoLapForUnreachedSteps() {
		User athlete = newAthlete("lap-derivation-short@example.cc");
		Activity activity = newActivity(athlete, Instant.parse("2026-01-03T06:00:00Z"));
		Workout workout = newGorbyWorkout(athlete);
		seedRecords(activity, activity.getStartDate(), 900); // warmup + first work interval only

		List<Lap> laps = lapDerivationService.deriveLaps(activity, workout);

		assertThat(laps).hasSize(2);
		assertThat(laps.get(0).getWorkoutStep().getKind()).isEqualTo(StepKind.WARMUP);
		assertThat(laps.get(1).getWorkoutStep().getKind()).isEqualTo(StepKind.BLOCK);
		assertThat(laps.get(1).getRepeatIndex()).isEqualTo(1);
	}

	@Test
	void manualEndedStepAnywhereInThePlanReturnsNull() {
		User athlete = newAthlete("lap-derivation-manual@example.cc");
		Activity activity = newActivity(athlete, Instant.parse("2026-01-04T06:00:00Z"));
		Workout workout = new Workout();
		workout.setCreatedBy(athlete);
		workout.setName("Has a manual step");
		workout.setSport(Sport.BIKE);
		WorkoutStep manual = new WorkoutStep();
		manual.setWorkout(workout);
		manual.setOrder(0);
		manual.setKind(StepKind.BLOCK);
		manual.setEndType(StepEndType.MANUAL);
		manual.setTargetType(TargetType.POWER);
		manual.setTargetLow(100.0);
		manual.setTargetHigh(100.0);
		workout.getSteps().add(manual);
		workout = workoutRepository.saveAndFlush(workout);
		seedRecords(activity, activity.getStartDate(), 600);

		assertThat(lapDerivationService.deriveLaps(activity, workout)).isNull();
	}

	@Test
	void noRecordsReturnsNull() {
		User athlete = newAthlete("lap-derivation-norecords@example.cc");
		Activity activity = newActivity(athlete, Instant.parse("2026-01-05T06:00:00Z"));
		Workout workout = newGorbyWorkout(athlete);

		assertThat(lapDerivationService.deriveLaps(activity, workout)).isNull();
	}

	@Test
	void replaceLapsWithDerivedReplacesExistingLaps() {
		User athlete = newAthlete("lap-derivation-replace@example.cc");
		Activity activity = newActivity(athlete, Instant.parse("2026-01-06T06:00:00Z"));
		Workout workout = newGorbyWorkout(athlete);
		seedRecords(activity, activity.getStartDate(), 3601);
		Lap existing = new Lap();
		existing.setActivity(activity);
		existing.setIndex(1);
		existing.setDuration(3600);
		existing.setDistanceKm(36.0);
		existing.setAvgPower(200);
		lapRepository.save(existing);

		boolean result = lapDerivationService.replaceLapsWithDerived(activity, workout);

		assertThat(result).isTrue();
		// Step-linking correctness itself is already covered by deriveLaps's own tests above;
		// this just checks "replace" actually replaced (1 stale lap -> 11 fresh ones), reading
		// back via a plain repository call outside any transaction - touching the lazy
		// workoutStep relation here would throw LazyInitializationException, not a real bug.
		assertThat(lapRepository.findByActivityIdOrderByIndex(activity.getId())).hasSize(11);
	}

	@Test
	void replaceLapsWithDerivedLeavesExistingLapsUntouchedWhenDerivationIsNotPossible() {
		User athlete = newAthlete("lap-derivation-replace-noop@example.cc");
		Activity activity = newActivity(athlete, Instant.parse("2026-01-07T06:00:00Z"));
		Workout workout = newGorbyWorkout(athlete); // no records seeded
		Lap existing = new Lap();
		existing.setActivity(activity);
		existing.setIndex(1);
		existing.setDuration(3600);
		existing.setDistanceKm(36.0);
		existing.setAvgPower(200);
		lapRepository.save(existing);

		boolean result = lapDerivationService.replaceLapsWithDerived(activity, workout);

		assertThat(result).isFalse();
		assertThat(lapRepository.findByActivityIdOrderByIndex(activity.getId())).hasSize(1);
	}
}
