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
import com.cadence.api.workouts.WorkoutMatchScanService.RankedWorkout;
import com.cadence.api.workouts.WorkoutMatchScanService.Segment;
import java.time.Instant;
import java.util.List;
import java.util.Set;
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
		// Matches the fixed structure below (600 warmup + 5x(300 work + 300 rec)) - normally kept
		// in sync by the workout create/update endpoint's duration/TSS computation, which this
		// direct-entity fixture bypasses; runScanSync's duration pre-filter now reads this
		// persisted column directly (see WorkoutMatchScanService), so it has to be right here too.
		workout.setDuration(3600);

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

	/** warmup 300s@60%, then 3x[block 1000m@110%, rec 500m@52.5%] - the distance-ended
	 * counterpart to {@link #newGorbyWorkout}, for exercising {@code correlateRecordsByStepBoundary}.
	 * At the constant 4m/s {@link #seedMatchingRecordsWithDistance} assumes, the block/rec steps
	 * take exactly 250s/125s each, so duration=1425 (300 + 3*(250+125)) is exact, matching the
	 * same convention {@link #newGorbyWorkout} documents. */
	private Workout newDistanceGorbyWorkout(User athlete) {
		Workout workout = new Workout();
		workout.setCreatedBy(athlete);
		workout.setName("Distance Gorby");
		workout.setSport(Sport.RUN);
		workout.setDuration(1425);

		WorkoutStep warmup = leaf(workout, null, 0, StepKind.WARMUP, 300, 60.0, 60.0);
		WorkoutStep group = new WorkoutStep();
		group.setWorkout(workout);
		group.setOrder(1);
		group.setKind(StepKind.REPEAT);
		group.setRepeat(3);
		WorkoutStep work = leaf(workout, group, 0, StepKind.BLOCK, 300, 110.0, 110.0);
		work.setEndType(StepEndType.DISTANCE);
		work.setDuration(null);
		work.setDistance(1000);
		WorkoutStep rest = leaf(workout, group, 1, StepKind.REC, 300, 52.5, 52.5);
		rest.setEndType(StepEndType.DISTANCE);
		rest.setDuration(null);
		rest.setDistance(500);
		workout.getSteps().add(warmup);
		workout.getSteps().add(group);
		workout.getSteps().add(work);
		workout.getSteps().add(rest);
		return workoutRepository.saveAndFlush(workout);
	}

	private static int phasePowerDistance(int t, double ftp) {
		if (t < 300) {
			return (int) Math.round(0.60 * ftp);
		}
		int repT = (t - 300) % 375;
		return (int) Math.round((repT < 250 ? 1.10 : 0.525) * ftp);
	}

	private void seedMatchingRecordsWithDistance(Activity activity, Instant start, int totalSeconds, double ftp,
			double speedMps) {
		for (int t = 0; t < totalSeconds; t++) {
			Record record = new Record();
			record.setId(new RecordId(activity.getId(), start.plusSeconds(t)));
			record.setActivity(activity);
			record.setT(t);
			record.setPower(phasePowerDistance(t, ftp));
			record.setDistanceKm(t * speedMps / 1000);
			recordRepository.save(record);
		}
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
	void scannabilityErrorIgnoresAnExcludedStepsOwnValidity() {
		// A manual-ended cooldown would normally fail scannability - but not if the caller has
		// already chosen to exclude cooldowns entirely, since that step never reaches curve
		// construction either way.
		User athlete = newAthlete("manual-cooldown-excluded@example.cc");
		Workout workout = new Workout();
		workout.setCreatedBy(athlete);
		workout.setName("Manual cooldown");
		workout.setSport(Sport.BIKE);
		WorkoutStep work = leaf(workout, null, 0, StepKind.BLOCK, 300, 100.0, 100.0);
		WorkoutStep cooldown = leaf(workout, null, 1, StepKind.COOL, 300, 50.0, 50.0);
		cooldown.setEndType(StepEndType.MANUAL);
		cooldown.setDuration(null);
		workout.getSteps().add(work);
		workout.getSteps().add(cooldown);
		workout = workoutRepository.saveAndFlush(workout);

		assertThat(workoutMatchScanService.scannabilityError(workout.getId())).isNotNull();
		assertThat(workoutMatchScanService.scannabilityError(workout.getId(), Set.of(StepKind.COOL))).isNull();
	}

	@Test
	void scannabilityErrorAllowsADistanceEndTypeStepIfPowerTargeted() {
		User athlete = newAthlete("distance-scannable@example.cc");
		Workout workout = new Workout();
		workout.setCreatedBy(athlete);
		workout.setName("Distance");
		workout.setSport(Sport.BIKE);
		WorkoutStep step = leaf(workout, null, 0, StepKind.BLOCK, 300, 100.0, 100.0);
		step.setEndType(StepEndType.DISTANCE);
		step.setDuration(null);
		step.setDistance(5000);
		workout.getSteps().add(step);
		workout = workoutRepository.saveAndFlush(workout);

		assertThat(workoutMatchScanService.scannabilityError(workout.getId())).isNull();
	}

	@Test
	void scannabilityErrorRejectsADistanceStepWithNoDistanceValue() {
		User athlete = newAthlete("distance-no-value@example.cc");
		Workout workout = new Workout();
		workout.setCreatedBy(athlete);
		workout.setName("Distance, no value");
		workout.setSport(Sport.BIKE);
		WorkoutStep step = leaf(workout, null, 0, StepKind.BLOCK, 300, 100.0, 100.0);
		step.setEndType(StepEndType.DISTANCE);
		step.setDuration(null);
		step.setDistance(null);
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
	void buildExpectedCurveExcludesAKindButKeepsLaterOffsetsAbsolute() {
		User athlete = newAthlete("curve-excluded@example.cc");
		Workout workout = newGorbyWorkout(athlete);

		List<Segment> curve = workoutMatchScanService.buildExpectedCurve(workout.getId(), Set.of(StepKind.WARMUP));

		// The leading warmup segment is gone entirely, but every remaining segment keeps its
		// original 600-3600 timeline position - not renumbered to start at 0 - since the real
		// activity recording still covers that first 600s in real time.
		assertThat(curve).containsExactly(
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
	void sporadicZeroPowerDropoutsAreExcludedNotPenalized() {
		// Regression coverage for a real activity found live: a sensor/connection dropout reads
		// as power=0 for a few seconds at a time (confirmed against the raw FIT records -
		// cadence and heart rate carry on unaffected through the gap, so it isn't a genuine
		// stop), which was dragging a structurally-strong match down to a mediocre correlation
		// score. Zeros should be excluded like missing data, not paired as real "no effort"
		// samples.
		User athlete = newAthlete("correlate-dropouts@example.cc");
		Workout workout = newGorbyWorkout(athlete);
		List<Segment> curve = workoutMatchScanService.buildExpectedCurve(workout.getId());
		Instant start = Instant.parse("2026-01-01T06:00:00Z");
		Activity activity = newActivity(athlete, start, Sport.BIKE, 3600, null);
		seedMatchingRecords(activity, start, 3601, 250);
		// Sprinkle in dropouts throughout the ride, mirroring the real activity's pattern (many
		// short zero-power runs scattered across the whole duration, not clustered in one spot).
		List<Record> records = recordRepository.findByActivityIdOrderByT(activity.getId());
		for (Record record : records) {
			if (record.getT() % 137 == 50 % 137) {
				record.setPower(0);
				recordRepository.save(record);
			}
		}

		var result = workoutMatchScanService.correlateActivity(curve, activity);

		assertThat(result).isNotNull();
		assertThat(result.correlation()).isGreaterThan(0.99);
		assertThat(result.coverage()).isLessThan(1.0);
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
	void correlateRecordsByStepBoundaryScoresAPerfectlyMatchingActivityCloseToOne() {
		User athlete = newAthlete("correlate-distance-match@example.cc");
		Workout workout = newDistanceGorbyWorkout(athlete);
		Instant start = Instant.parse("2026-01-05T06:00:00Z");
		Activity activity = newActivity(athlete, start, Sport.RUN, 1425, null);
		seedMatchingRecordsWithDistance(activity, start, 1426, 250, 4.0);
		List<Record> records = recordRepository.findByActivityIdOrderByT(activity.getId());

		var result = workoutMatchScanService.correlateRecordsByStepBoundary(workout, records, 250, Set.of());

		assertThat(result).isNotNull();
		// Fewer, larger steps than the time-based Gorby fixture (3 reps of 2 steps vs 5), so
		// boundary-sample dilution costs slightly more of the correlation - still unambiguously
		// a near-perfect match.
		assertThat(result.correlation()).isGreaterThan(0.98);
		assertThat(result.coverage()).isEqualTo(1.0);
		assertThat(result.impliedFtp()).isCloseTo(250, org.assertj.core.data.Offset.offset(2));
	}

	@Test
	void correlateRecordsByStepBoundaryReturnsNullWithNoRecords() {
		User athlete = newAthlete("correlate-distance-empty@example.cc");
		Workout workout = newDistanceGorbyWorkout(athlete);

		assertThat(workoutMatchScanService.correlateRecordsByStepBoundary(workout, List.of(), 250, Set.of())).isNull();
	}

	@Test
	void correlateRecordsByStepBoundaryExcludedKindsStillAdvanceTheOffset() {
		// Mirrors buildCurveFor's own exclusion semantics: an excluded step's span is dropped
		// from the correlation, but the boundary walk still advances past it - a later step's
		// own distance boundary is measured from where the excluded step actually ended in the
		// real data, not as if it never existed.
		User athlete = newAthlete("correlate-distance-excluded@example.cc");
		Workout workout = newDistanceGorbyWorkout(athlete);
		Instant start = Instant.parse("2026-01-06T06:00:00Z");
		Activity activity = newActivity(athlete, start, Sport.RUN, 1425, null);
		seedMatchingRecordsWithDistance(activity, start, 1426, 250, 4.0);
		List<Record> records = recordRepository.findByActivityIdOrderByT(activity.getId());

		var result = workoutMatchScanService.correlateRecordsByStepBoundary(workout, records, 250, Set.of(StepKind.WARMUP));

		assertThat(result).isNotNull();
		assertThat(result.correlation()).isGreaterThan(0.98);
		assertThat(result.coverage()).isLessThan(1.0); // the warmup's 300 samples are dropped from the numerator
	}

	@Test
	void runScanFindsTheMatchingActivityForADistanceEndedWorkout() {
		User athlete = newAthlete("scan-distance-athlete@example.cc");
		Workout workout = newDistanceGorbyWorkout(athlete);
		Instant start = Instant.parse("2026-01-07T06:00:00Z");

		Activity match = newActivity(athlete, start, Sport.RUN, 1425, null);
		seedMatchingRecordsWithDistance(match, start, 1426, 250, 4.0);

		Activity wrongSport = newActivity(athlete, start, Sport.BIKE, 1425, null);
		seedMatchingRecordsWithDistance(wrongSport, start, 1426, 250, 4.0);

		WorkoutMatchScan scan = new WorkoutMatchScan();
		scan.setWorkout(workout);
		scan = scanRepository.save(scan);

		workoutMatchScanService.runScanSync(scan.getId());

		WorkoutMatchScan finished = scanRepository.findById(scan.getId()).orElseThrow();
		assertThat(finished.getStatus()).isEqualTo(WorkoutMatchScanStatus.READY);

		List<WorkoutMatchScanCandidate> candidates =
				candidateRepository.findByScanIdOrderByCorrelationDescFetchActivity(scan.getId());
		assertThat(candidates).hasSize(1);
		assertThat(candidates.get(0).getActivity().getId()).isEqualTo(match.getId());
		assertThat(candidates.get(0).getCorrelation()).isGreaterThan(0.98);
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

	/** The mirror image of the tests above: one activity ranked against many candidate
	 * workouts, instead of one workout against many candidate activities. Backs the activity ->
	 * workout-library endpoint and the ingest-time auto-match tie-break. */
	@Test
	void rankWorkoutsForActivityRanksTheCorrelatingWorkoutFirst() {
		User athlete = newAthlete("rank-athlete@example.cc");
		Workout matching = newGorbyWorkout(athlete);
		matching.setDuration(3600);
		matching = workoutRepository.saveAndFlush(matching);
		Workout flat = new Workout();
		flat.setCreatedBy(athlete);
		flat.setName("Flat");
		flat.setSport(Sport.BIKE);
		flat.setDuration(3600);
		WorkoutStep flatStep = new WorkoutStep();
		flatStep.setWorkout(flat);
		flatStep.setOrder(0);
		flatStep.setKind(StepKind.BLOCK);
		flatStep.setEndType(StepEndType.TIME);
		flatStep.setDuration(3600);
		flatStep.setTargetType(TargetType.POWER);
		flatStep.setTargetLow(70.0);
		flatStep.setTargetHigh(70.0);
		flat.getSteps().add(flatStep);
		flat = workoutRepository.saveAndFlush(flat);
		Instant start = Instant.parse("2026-01-08T06:00:00Z");
		Activity activity = newActivity(athlete, start, Sport.BIKE, 3600, null);
		seedMatchingRecords(activity, start, 3601, 250);

		List<RankedWorkout> ranked = workoutMatchScanService.rankWorkoutsForActivity(List.of(flat, matching), activity);

		// flat is excluded entirely (zero-variance curve, undefined correlation), so matching
		// isn't just ranked first, it's the only survivor.
		assertThat(ranked).hasSize(1);
		assertThat(ranked.get(0).workout().getId()).isEqualTo(matching.getId());
		assertThat(ranked.get(0).correlation()).isGreaterThan(0.99);
	}

	@Test
	void rankWorkoutsForActivityExcludesADurationMismatchBeforeFetchingSteps() {
		User athlete = newAthlete("rank-duration-athlete@example.cc");
		Instant start = Instant.parse("2026-01-09T06:00:00Z");
		Activity activity = newActivity(athlete, start, Sport.BIKE, 3600, null);
		seedMatchingRecords(activity, start, 3601, 250);
		Workout tooShort = new Workout();
		tooShort.setCreatedBy(athlete);
		tooShort.setName("Short");
		tooShort.setSport(Sport.BIKE);
		tooShort.setDuration(600);
		tooShort = workoutRepository.saveAndFlush(tooShort);

		assertThat(workoutMatchScanService.rankWorkoutsForActivity(List.of(tooShort), activity)).isEmpty();
	}

	@Test
	void rankWorkoutsForActivityReturnsEmptyWithNoRecords() {
		User athlete = newAthlete("rank-norecords-athlete@example.cc");
		Workout matching = newGorbyWorkout(athlete);
		matching.setDuration(3600);
		matching = workoutRepository.saveAndFlush(matching);
		Activity activity = newActivity(athlete, Instant.parse("2026-01-10T06:00:00Z"), Sport.BIKE, 3600, null);

		assertThat(workoutMatchScanService.rankWorkoutsForActivity(List.of(matching), activity)).isEmpty();
	}
}
