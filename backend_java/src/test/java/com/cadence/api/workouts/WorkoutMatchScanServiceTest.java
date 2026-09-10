package com.cadence.api.workouts;

import static org.assertj.core.api.Assertions.assertThat;

import com.cadence.api.activities.Activity;
import com.cadence.api.activities.ActivityRepository;
import com.cadence.api.activities.Record;
import com.cadence.api.activities.RecordId;
import com.cadence.api.activities.RecordRepository;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.support.IntegrationTest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import com.cadence.api.workouts.WorkoutMatchScanService.Segment;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Same "warmup 600s@60%, then 5x[work 300s@110%, rest 300s@52.5%]" structure as the real "The
 * Gorby" workout this whole feature was validated against this session.
 */
class WorkoutMatchScanServiceTest extends IntegrationTest {

	@Autowired
	private WorkoutMatchScanService workoutMatchScanService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private WorkoutRepository workoutRepository;

	@Autowired
	private ActivityRepository activityRepository;

	@Autowired
	private RecordRepository recordRepository;

	@Autowired
	private WorkoutMatchScanRepository scanRepository;

	@Autowired
	private WorkoutMatchScanCandidateRepository candidateRepository;

	private User newAthlete(String email) {
		User user = new User();
		user.setEmail(email);
		user.setName("Test Athlete");
		user.setPassword("irrelevant-for-this-test");
		return userRepository.save(user);
	}

	private Workout newGorbyWorkout(User athlete) {
		Workout workout = new Workout();
		workout.setCreatedBy(athlete);
		workout.setName("The Gorby");
		workout.setSport(Sport.BIKE);

		WorkoutStep warmup = leaf(workout, null, 0, StepKind.WARMUP, 600, 60.0, 60.0);
		WorkoutStep group = new WorkoutStep();
		group.setWorkout(workout);
		group.setOrder(1);
		group.setKind(StepKind.REPEAT);
		group.setRepeat(5);
		WorkoutStep work = leaf(workout, group, 0, StepKind.BLOCK, 300, 110.0, 110.0);
		WorkoutStep rest = leaf(workout, group, 1, StepKind.REC, 300, 52.5, 52.5);
		// One cascade save (not staged across several) - work/rest reference group by object
		// identity while it's still transient, which only resolves correctly within a single,
		// uninterrupted persistence-context flush (see this session's earlier notes on
		// TransientPropertyValueException).
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

	private Activity newActivity(User athlete, Instant start, Sport sport, int movingTime, Workout workout) {
		Activity activity = new Activity();
		activity.setAthlete(athlete);
		activity.setSport(sport);
		activity.setName("Ride");
		activity.setStartDate(start);
		activity.setMovingTime(movingTime);
		activity.setWorkout(workout);
		return activityRepository.save(activity);
	}

	private static int phasePower(int t, double ftp) {
		if (t < 600) {
			return (int) Math.round(0.60 * ftp);
		}
		int repT = (t - 600) % 600;
		return (int) Math.round((repT < 300 ? 1.10 : 0.525) * ftp);
	}

	private void seedMatchingRecords(Activity activity, Instant start, int totalSeconds, double ftp) {
		for (int t = 0; t < totalSeconds; t++) {
			Record record = new Record();
			record.setId(new RecordId(activity.getId(), start.plusSeconds(t)));
			record.setActivity(activity);
			record.setT(t);
			record.setPower(phasePower(t, ftp));
			recordRepository.save(record);
		}
	}

	@Test
	void scannabilityErrorIsNullForAPowerAndDurationBasedWorkout() {
		User athlete = newAthlete("scannable@example.cc");
		Workout workout = newGorbyWorkout(athlete);

		assertThat(workoutMatchScanService.scannabilityError(workout.getId())).isNull();
	}

	@Test
	void scannabilityErrorRejectsAPaceTargetStep() {
		User athlete = newAthlete("pace-not-scannable@example.cc");
		Workout workout = new Workout();
		workout.setCreatedBy(athlete);
		workout.setName("Pace run");
		workout.setSport(Sport.RUN);
		WorkoutStep step = leaf(workout, null, 0, StepKind.BLOCK, 1200, 90.0, 90.0);
		step.setTargetType(TargetType.PACE);
		workout.getSteps().add(step);
		workout = workoutRepository.saveAndFlush(workout);

		assertThat(workoutMatchScanService.scannabilityError(workout.getId())).isNotNull();
	}

	@Test
	void scannabilityErrorRejectsAManualEndTypeStep() {
		User athlete = newAthlete("manual-not-scannable@example.cc");
		Workout workout = new Workout();
		workout.setCreatedBy(athlete);
		workout.setName("Manual");
		workout.setSport(Sport.BIKE);
		WorkoutStep step = leaf(workout, null, 0, StepKind.BLOCK, 300, 100.0, 100.0);
		step.setEndType(StepEndType.MANUAL);
		step.setDuration(null);
		workout.getSteps().add(step);
		workout = workoutRepository.saveAndFlush(workout);

		assertThat(workoutMatchScanService.scannabilityError(workout.getId())).isNotNull();
	}

	@Test
	void scannabilityErrorRejectsAWorkoutWithNoSteps() {
		User athlete = newAthlete("empty-not-scannable@example.cc");
		Workout workout = new Workout();
		workout.setCreatedBy(athlete);
		workout.setName("Empty");
		workout.setSport(Sport.BIKE);
		workout = workoutRepository.saveAndFlush(workout);

		assertThat(workoutMatchScanService.scannabilityError(workout.getId())).isNotNull();
	}

	@Test
	void buildExpectedCurveLeavesPctFtpStepsUnchanged() {
		User athlete = newAthlete("curve-pct@example.cc");
		Workout workout = newGorbyWorkout(athlete);

		List<Segment> curve = workoutMatchScanService.buildExpectedCurve(workout.getId());

		assertThat(curve).containsExactly(
				new Segment(0, 600, 60.0),
				new Segment(600, 900, 110.0), new Segment(900, 1200, 52.5),
				new Segment(1200, 1500, 110.0), new Segment(1500, 1800, 52.5),
				new Segment(1800, 2100, 110.0), new Segment(2100, 2400, 52.5),
				new Segment(2400, 2700, 110.0), new Segment(2700, 3000, 52.5),
				new Segment(3000, 3300, 110.0), new Segment(3300, 3600, 52.5));
	}

	@Test
	void buildExpectedCurveConvertsAWattsUnitStepUsingTheAthletesFtp() {
		User athlete = newAthlete("curve-watts@example.cc");
		athlete.setFtp(250);
		athlete = userRepository.save(athlete);
		Workout workout = new Workout();
		workout.setCreatedBy(athlete);
		workout.setName("Watts");
		workout.setSport(Sport.BIKE);
		WorkoutStep step = leaf(workout, null, 0, StepKind.BLOCK, 300, 275.0, 275.0);
		step.setPowerUnit(PowerUnit.WATTS);
		workout.getSteps().add(step);
		workout = workoutRepository.saveAndFlush(workout);

		List<Segment> curve = workoutMatchScanService.buildExpectedCurve(workout.getId());

		assertThat(curve).hasSize(1);
		assertThat(curve.get(0).startS()).isEqualTo(0);
		assertThat(curve.get(0).endS()).isEqualTo(300);
		assertThat(curve.get(0).intensityPct()).isCloseTo(110.0, org.assertj.core.data.Offset.offset(0.001));
	}

	@Test
	void correlateActivityScoresAPerfectlyMatchingActivityCloseToOne() {
		User athlete = newAthlete("correlate-match@example.cc");
		Workout workout = newGorbyWorkout(athlete);
		List<Segment> curve = workoutMatchScanService.buildExpectedCurve(workout.getId());
		Instant start = Instant.parse("2026-01-01T06:00:00Z");
		Activity activity = newActivity(athlete, start, Sport.BIKE, 3600, null);
		seedMatchingRecords(activity, start, 3601, 250);

		var result = workoutMatchScanService.correlateActivity(curve, activity);

		assertThat(result).isNotNull();
		assertThat(result.correlation()).isGreaterThan(0.99);
		assertThat(result.coverage()).isEqualTo(1.0);
		assertThat(result.impliedFtp()).isCloseTo(250, org.assertj.core.data.Offset.offset(2));
	}

	@Test
	void correlateActivityReturnsNullForConstantPowerAgainstAVaryingTarget() {
		User athlete = newAthlete("correlate-constant@example.cc");
		Workout workout = newGorbyWorkout(athlete);
		List<Segment> curve = workoutMatchScanService.buildExpectedCurve(workout.getId());
		Instant start = Instant.parse("2026-01-02T06:00:00Z");
		Activity activity = newActivity(athlete, start, Sport.BIKE, 3600, null);
		for (int t = 0; t < 3601; t++) {
			Record record = new Record();
			record.setId(new RecordId(activity.getId(), start.plusSeconds(t)));
			record.setActivity(activity);
			record.setT(t);
			record.setPower(180);
			recordRepository.save(record);
		}

		assertThat(workoutMatchScanService.correlateActivity(curve, activity)).isNull();
	}

	@Test
	void correlateActivityReturnsNullWithNoRecords() {
		User athlete = newAthlete("correlate-empty@example.cc");
		Workout workout = newGorbyWorkout(athlete);
		List<Segment> curve = workoutMatchScanService.buildExpectedCurve(workout.getId());
		Activity activity = newActivity(athlete, Instant.parse("2026-01-03T06:00:00Z"), Sport.BIKE, 3600, null);

		assertThat(workoutMatchScanService.correlateActivity(curve, activity)).isNull();
	}

	@Test
	void runScanFindsTheMatchingActivityAndExcludesUnrelatedCandidates() {
		User athlete = newAthlete("scan-athlete@example.cc");
		Workout workout = newGorbyWorkout(athlete);
		Instant start = Instant.parse("2026-01-04T06:00:00Z");

		Activity match = newActivity(athlete, start, Sport.BIKE, 3600, null);
		seedMatchingRecords(match, start, 3601, 250);

		Activity wrongSport = newActivity(athlete, start, Sport.RUN, 3600, null);
		seedMatchingRecords(wrongSport, start, 3601, 250);

		Activity alreadyMatched = newActivity(athlete, start, Sport.BIKE, 3600, workout);
		seedMatchingRecords(alreadyMatched, start, 3601, 250);

		Activity tooShort = newActivity(athlete, start, Sport.BIKE, 1200, null);
		seedMatchingRecords(tooShort, start, 1201, 250);

		WorkoutMatchScan scan = new WorkoutMatchScan();
		scan.setWorkout(workout);
		scan = scanRepository.save(scan);

		workoutMatchScanService.runScanSync(scan.getId());

		WorkoutMatchScan finished = scanRepository.findById(scan.getId()).orElseThrow();
		assertThat(finished.getStatus()).isEqualTo(WorkoutMatchScanStatus.READY);
		assertThat(finished.getProcessedCandidates()).isEqualTo(1); // only `match` passes the duration pre-filter

		List<WorkoutMatchScanCandidate> candidates =
				candidateRepository.findByScanIdOrderByCorrelationDescFetchActivity(scan.getId());
		assertThat(candidates).hasSize(1);
		assertThat(candidates.get(0).getActivity().getId()).isEqualTo(match.getId());
		assertThat(candidates.get(0).getCorrelation()).isGreaterThan(0.99);
	}
}
