package com.cadence.api.workouts;

import com.cadence.api.activities.Activity;
import com.cadence.api.activities.ActivityRepository;
import com.cadence.api.activities.Record;
import com.cadence.api.activities.RecordRepository;
import com.cadence.api.athletes.ZoneService;
import com.cadence.api.athletes.ZoneType;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.common.error.NotFoundException;
import com.cadence.api.users.User;
import com.cadence.api.users.UserService;
import com.cadence.api.workouts.WorkoutStepFlattener.Flattened;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Scans a workout's athlete's own unmatched, same-sport activities for likely matches, by
 * correlating (Pearson) each candidate's actual per-second power stream against the workout's
 * planned %FTP-vs-time curve. See {@link WorkoutMatchScan}'s Javadoc for why this needs to be a
 * background job rather than a synchronous request.
 *
 * <p>v1 only supports power-target workouts (every flattened leaf step must have {@code
 * TargetType.POWER}) - pace-target workouts read too noisily off GPS/treadmill speed to trust
 * at the same confidence threshold validated for power (no forced compliance mechanism the way
 * ERG mode holds power steady), so they're intentionally out of scope for now. Follow-up:
 * validate against a real pace-based workout before adding support.
 *
 * <p>Both {@code TIME}- and {@code DISTANCE}-ended steps are supported, but need two different
 * strategies: a {@code TIME}-ended step's boundary is the same for every candidate (it's baked
 * into the plan), so {@link #buildCurveFor}/{@link #correlateRecords} build one reusable
 * (startS, endS, intensityPct) curve per workout and correlate many candidates against it. A
 * {@code DISTANCE}-ended step's boundary depends on how fast *this specific* candidate actually
 * covered that distance - there's no single time-based curve that's valid for every candidate -
 * so a workout with any distance-ended step instead walks each candidate's own record stream
 * directly ({@link #correlateRecordsByStepBoundary}), assigning each record to whichever step
 * it falls in the same dual time-or-distance way {@code LapDerivationService} slices a matched
 * activity's laps, and correlating as it goes.
 *
 * <p>Validated this session against a real distance-ended run-power workout (4 distance blocks
 * at 80/90/97/105% CP with timed recoveries) and its real matched activity: the boundary walk
 * itself tracked essentially exactly (each block's real distance covered within ~1% of its
 * planned distance, power rising monotonically block-to-block with the target). The resulting
 * correlation (~0.55-0.65) reads noticeably lower than bike power's validated ~0.87 baseline
 * though - not a bug in the boundary logic, but a real property of running power meters:
 * there's a large fixed biomechanical cost to running at any pace (unlike a bike, which can
 * freewheel to near-zero power), so an easy recovery jog's actual power compresses far less
 * below a hard interval's than the %-of-threshold target design assumes. Still clearly useful
 * for <em>ranking</em> candidates (an unrelated activity scores far lower still), just don't
 * expect run-power distance-interval scans to read as high in absolute terms as bike's.
 */
@Service
public class WorkoutMatchScanService {

	private static final Logger log = LoggerFactory.getLogger(WorkoutMatchScanService.class);

	// Validated this session against real historical data: three independent matches all held
	// r >= 0.87 with a duration diff of 0-12s, while widening the window to +/-60s only ever
	// added noise-floor candidates (r <= ~0.52), never a false positive - ranking is by
	// correlation, not by inclusion, so a generous width costs nothing but a few extra rows.
	private static final int DURATION_TOLERANCE_SECONDS = 60;

	// Mirrors WorkoutCalculations.DEFAULT_POWER_REFERENCE - kept as its own copy since that
	// one is private to its own class.
	private static final double DEFAULT_POWER_REFERENCE = 265;

	private final WorkoutRepository workoutRepository;
	private final ActivityRepository activityRepository;
	private final RecordRepository recordRepository;
	private final WorkoutMatchScanRepository scanRepository;
	private final WorkoutMatchScanCandidateRepository candidateRepository;
	private final UserService userService;
	private final ZoneService zoneService;
	private final WorkoutMatchScanProgressUpdater progressUpdater;

	public WorkoutMatchScanService(WorkoutRepository workoutRepository, ActivityRepository activityRepository,
			RecordRepository recordRepository, WorkoutMatchScanRepository scanRepository,
			WorkoutMatchScanCandidateRepository candidateRepository, UserService userService, ZoneService zoneService,
			WorkoutMatchScanProgressUpdater progressUpdater) {
		this.workoutRepository = workoutRepository;
		this.activityRepository = activityRepository;
		this.recordRepository = recordRepository;
		this.scanRepository = scanRepository;
		this.candidateRepository = candidateRepository;
		this.userService = userService;
		this.zoneService = zoneService;
		this.progressUpdater = progressUpdater;
	}

	public record Segment(int startS, int endS, double intensityPct) {
	}

	public record CorrelationResult(double correlation, double coverage, Integer impliedFtp) {
	}

	public record RankedWorkout(Workout workout, double correlation, double coverage, Integer impliedFtp) {
	}

	/** {@code null} if the workout can be scanned for matches; otherwise the reason it can't,
	 * suitable for a 400 response. */
	public String scannabilityError(String workoutId) {
		return scannabilityError(workoutId, Set.of());
	}

	/** Same as {@link #scannabilityError(String)}, but {@code excludedKinds} (leaf {@link
	 * StepKind} values, e.g. warmup/cool) are skipped entirely before validation - a step that
	 * won't be used to build the curve shouldn't be able to disqualify the whole workout (e.g. a
	 * distance-ended cooldown the caller has chosen to exclude anyway). */
	public String scannabilityError(String workoutId, Set<StepKind> excludedKinds) {
		return scannabilityErrorFor(fetchWithSteps(workoutId), excludedKinds);
	}

	private String scannabilityErrorFor(Workout workout, Set<StepKind> excludedKinds) {
		List<Flattened> flattened = WorkoutStepFlattener.flatten(workout).stream()
				.filter(f -> !excludedKinds.contains(f.step().getKind()))
				.toList();
		if (flattened.isEmpty()) {
			return "This workout has no steps to scan against.";
		}
		for (Flattened f : flattened) {
			WorkoutStep step = f.step();
			if (step.getTargetType() != TargetType.POWER) {
				return "Only power-target workouts can be scanned for matches right now.";
			}
			if (step.getEndType() == StepEndType.TIME && step.getDuration() == null) {
				return "Every time-ended step needs a duration to be scanned.";
			}
			if (step.getEndType() == StepEndType.DISTANCE && step.getDistance() == null) {
				return "Every distance-ended step needs a distance to be scanned.";
			}
			if (step.getEndType() == StepEndType.MANUAL) {
				return "Manual-ended steps can't be scanned for matches - there's no derivable boundary.";
			}
		}
		return null;
	}

	/** Whether {@code flattened} contains any distance-ended step - see the class Javadoc for
	 * why that forces the per-candidate boundary walk instead of one reusable time-based
	 * curve. */
	private static boolean needsDistanceWalk(List<Flattened> flattened) {
		return flattened.stream().anyMatch(f -> f.step().getEndType() == StepEndType.DISTANCE);
	}

	/** Cumulative {@code (startS, endS, intensityPct)} segments for the workout's flattened
	 * steps - {@link #scannabilityError} must already have confirmed every step is
	 * power/duration-based. {@code intensityPct} is always expressed as %FTP: a
	 * {@code WATTS}-unit step is converted using the athlete's current bike/run power reference
	 * (falling back to the same default {@code WorkoutCalculations.normalizePowerUnits} uses
	 * when the athlete hasn't set one). The exact reference value barely matters for the
	 * correlation itself - Pearson is invariant to any single consistent rescale of the whole
	 * curve - it only affects the informational {@code impliedFtp} reported back per candidate.
	 */
	public List<Segment> buildExpectedCurve(String workoutId) {
		return buildExpectedCurve(workoutId, Set.of());
	}

	/** Same as {@link #buildExpectedCurve(String)}, but {@code excludedKinds} (leaf {@link
	 * StepKind} values) skip emitting a curve segment for that step - see
	 * {@link #buildCurveFor(Workout, double, Set)} for how the timeline stays aligned. */
	public List<Segment> buildExpectedCurve(String workoutId, Set<StepKind> excludedKinds) {
		Workout workout = fetchWithSteps(workoutId);
		return buildCurveFor(workout, referenceFor(workout), excludedKinds);
	}

	private List<Segment> buildCurveFor(Workout workout) {
		return buildCurveFor(workout, referenceFor(workout), Set.of());
	}

	/** Same as {@link #buildCurveFor(Workout)}, but takes an already-resolved power-zone
	 * reference - for a caller ranking many candidate workouts for the same athlete (see
	 * {@link #rankWorkoutsForActivity}), where looking it up once and reusing it avoids an
	 * identical {@code userService}/{@code zoneService} round-trip per candidate.
	 *
	 * <p>{@code excludedKinds} (leaf {@link StepKind} values) skip appending a curve segment for
	 * that step, but the running {@code offset} still advances past its duration - later
	 * segments keep their correct absolute position in the workout's timeline (the activity
	 * recording still covers the excluded phase in real time; {@code sampleExpectedAt} already
	 * returns {@code null} for any {@code t} no segment covers, so no other change is needed to
	 * make those samples fall out of the correlation).
	 */
	private List<Segment> buildCurveFor(Workout workout, double reference, Set<StepKind> excludedKinds) {
		List<Segment> curve = new ArrayList<>();
		int offset = 0;
		for (Flattened f : WorkoutStepFlattener.flatten(workout)) {
			WorkoutStep step = f.step();
			if (!excludedKinds.contains(step.getKind())) {
				curve.add(new Segment(offset, offset + step.getDuration(), stepIntensityPct(step, reference)));
			}
			offset += step.getDuration();
		}
		return curve;
	}

	/** A single step's expected intensity, always expressed as %FTP - see {@link #buildCurveFor}
	 * for the exact meaning. Shared between {@link #buildCurveFor} (every step time-ended, one
	 * curve reusable across candidates) and {@link #correlateRecordsByStepBoundary} (any step
	 * distance-ended, walked fresh per candidate). */
	private static double stepIntensityPct(WorkoutStep step, double reference) {
		double low = step.getTargetLow() != null ? step.getTargetLow() : 0.0;
		double high = step.getTargetHigh() != null ? step.getTargetHigh() : low;
		double mid = (low + high) / 2;
		return step.getPowerUnit() == PowerUnit.WATTS ? (mid / reference) * 100 : mid;
	}

	private double referenceFor(Workout workout) {
		User athlete = userService.getById(workout.getCreatedBy().getId());
		ZoneType zoneType = workout.getSport() == Sport.BIKE ? ZoneType.BIKE_POWER : ZoneType.RUN_POWER;
		Double reference = zoneService.referenceFor(athlete, zoneType);
		return reference != null ? reference : DEFAULT_POWER_REFERENCE;
	}

	// Fetch-joins steps - open-in-view is off, and WorkoutStepFlattener.flatten walks
	// workout.getSteps(), which would otherwise throw LazyInitializationException once this
	// method's own implicit transaction closes (same class of bug fixed in
	// LapRepository/LapController this session; WorkoutRepository.findByIdWithSteps already
	// existed for WorkoutController.getWorkout, reused here rather than duplicated).
	private Workout fetchWithSteps(String workoutId) {
		return workoutRepository.findByIdWithSteps(workoutId).orElseThrow(() -> new NotFoundException("No such workout."));
	}

	private Double sampleExpectedAt(List<Segment> curve, int t) {
		for (Segment s : curve) {
			if (t >= s.startS() && t < s.endS()) {
				return s.intensityPct();
			}
		}
		// t landing exactly on the plan's own total duration - the closing boundary of the last
		// segment, which the `t < endS` check above always excludes.
		if (!curve.isEmpty() && t == curve.get(curve.size() - 1).endS()) {
			return curve.get(curve.size() - 1).intensityPct();
		}
		return null;
	}

	/** {@code null} when there are too few paired samples, or either series is constant (a flat
	 * target or a dead-flat power reading can't be correlated - not an error, just undefined). */
	public static Double pearson(List<Double> xs, List<Double> ys) {
		int n = xs.size();
		if (n < 3) {
			return null;
		}
		double meanX = mean(xs);
		double meanY = mean(ys);
		double cov = 0, varX = 0, varY = 0;
		for (int i = 0; i < n; i++) {
			double dx = xs.get(i) - meanX;
			double dy = ys.get(i) - meanY;
			cov += dx * dy;
			varX += dx * dx;
			varY += dy * dy;
		}
		if (varX == 0 || varY == 0) {
			return null;
		}
		return cov / Math.sqrt(varX * varY);
	}

	private static double mean(List<Double> values) {
		double sum = 0;
		for (double v : values) {
			sum += v;
		}
		return sum / values.size();
	}

	/** Returns correlation/coverage/impliedFtp for {@code activity} against {@code curve}, or
	 * {@code null} if there's no usable overlap (no records, no power data, or nothing falls
	 * inside the workout's planned duration). Thin wrapper around {@link #correlateRecords} for
	 * a caller that only has one workout to check and hasn't already fetched the activity's
	 * records. */
	public CorrelationResult correlateActivity(List<Segment> curve, Activity activity) {
		return correlateRecords(curve, recordRepository.findByActivityIdOrderByT(activity.getId()));
	}

	/** Same as {@link #correlateActivity}, but takes an already-fetched, {@code t}-ordered
	 * record list - split out so a caller correlating one activity against many candidate
	 * workouts (see {@link #rankWorkoutsForActivity}) can fetch the activity's records once and
	 * reuse them, instead of re-fetching the same rows for every candidate.
	 *
	 * <p>A literal {@code 0} reading is treated the same as missing data (excluded, not paired
	 * as a real "no effort" sample): confirmed against a real activity's raw FIT records that a
	 * sensor/connection dropout reads as {@code power=0} for a few seconds at a time while
	 * cadence and heart rate carry on unaffected - real noise, not a genuine stop. No workout
	 * step ever targets 0 (every target is a positive %FTP/watts value), so this can't mask an
	 * intentionally flat zero-effort block; it only drops noise that would otherwise count as a
	 * correlation outlier against whatever the plan expects at that moment.
	 */
	public CorrelationResult correlateRecords(List<Segment> curve, List<Record> records) {
		if (records.isEmpty()) {
			return null;
		}
		int startT = records.get(0).getT();
		List<Double> xs = new ArrayList<>();
		List<Double> ys = new ArrayList<>();
		for (Record r : records) {
			if (r.getPower() == null || r.getPower() == 0) {
				continue;
			}
			Double expected = sampleExpectedAt(curve, r.getT() - startT);
			if (expected != null) {
				xs.add(expected);
				ys.add((double) r.getPower());
			}
		}
		return correlatePairs(xs, ys, records.size());
	}

	/** The {@code DISTANCE}-ended-step counterpart to {@link #correlateRecords}/{@link
	 * #buildCurveFor} - see the class Javadoc for why a distance-ended step can't be placed on a
	 * fixed, reusable time-based curve the way a time-ended one can. Walks {@code records}
	 * (already {@code t}-ordered) against {@code workout}'s own flattened steps directly,
	 * assigning each record to whichever step it falls in via the same dual time-or-distance
	 * boundary walk {@code LapDerivationService} uses to slice a matched activity's laps (a
	 * running {@code offsetT}/{@code offsetDistance} carried forward across steps, each step's
	 * own {@code endType} deciding which one governs its boundary), then correlates the
	 * resulting (expectedPct, actualPower) pairs.
	 *
	 * <p>Unlike lap derivation, this doesn't need pause-robust active-time accounting: a
	 * correlation over a full activity's worth of samples tolerates a handful of samples
	 * landing in the wrong step's bucket near a pause without materially moving the result,
	 * which a single lap's average power cannot.
	 */
	public CorrelationResult correlateRecordsByStepBoundary(Workout workout, List<Record> records, double reference,
			Set<StepKind> excludedKinds) {
		if (records.isEmpty()) {
			return null;
		}
		int n = records.size();
		int recordIdx = 0;
		int offsetT = records.get(0).getT();
		double offsetDistance = records.get(0).getDistanceKm() != null ? records.get(0).getDistanceKm() : 0.0;
		List<Double> xs = new ArrayList<>();
		List<Double> ys = new ArrayList<>();

		for (Flattened f : WorkoutStepFlattener.flatten(workout)) {
			if (recordIdx >= n) {
				break;
			}
			WorkoutStep step = f.step();
			int endIdx = recordIdx;
			if (step.getEndType() == StepEndType.TIME) {
				int boundary = offsetT + step.getDuration();
				while (endIdx < n - 1 && records.get(endIdx).getT() < boundary) {
					endIdx++;
				}
			}
			else {
				double boundary = offsetDistance + step.getDistance() / 1000.0;
				while (endIdx < n - 1
						&& (records.get(endIdx).getDistanceKm() == null || records.get(endIdx).getDistanceKm() < boundary)) {
					endIdx++;
				}
			}

			if (!excludedKinds.contains(step.getKind())) {
				double pct = stepIntensityPct(step, reference);
				for (Record r : records.subList(recordIdx, endIdx + 1)) {
					if (r.getPower() != null && r.getPower() != 0) {
						xs.add(pct);
						ys.add((double) r.getPower());
					}
				}
			}

			recordIdx = endIdx + 1;
			offsetT = records.get(endIdx).getT();
			if (records.get(endIdx).getDistanceKm() != null) {
				offsetDistance = records.get(endIdx).getDistanceKm();
			}
		}

		return correlatePairs(xs, ys, n);
	}

	/** Shared tail of {@link #correlateRecords} and {@link #correlateRecordsByStepBoundary}:
	 * turns paired (expectedPct, actualPower) series into a {@link CorrelationResult}, or {@code
	 * null} if there's nothing to correlate. {@code impliedFtp} is a regression-slope-derived
	 * value, informational only, never used for ranking. */
	private CorrelationResult correlatePairs(List<Double> xs, List<Double> ys, int totalRecords) {
		if (xs.isEmpty()) {
			return null;
		}
		Double r = pearson(xs, ys);
		if (r == null) {
			return null;
		}

		double meanX = mean(xs);
		double meanY = mean(ys);
		double varX = 0, cov = 0;
		for (int i = 0; i < xs.size(); i++) {
			double dx = xs.get(i) - meanX;
			varX += dx * dx;
			cov += dx * (ys.get(i) - meanY);
		}
		Integer impliedFtp = varX > 0 ? (int) Math.round((cov / varX) * 100) : null;

		double coverage = (double) xs.size() / totalRecords;
		return new CorrelationResult(round4(r), round4(coverage), impliedFtp);
	}

	/** Ranks {@code workouts} by Pearson correlation of {@code activity}'s actual power stream
	 * against each one's planned %FTP-vs-time curve - the mirror image of {@link #runScanSync}
	 * (one activity vs. many candidate workouts, instead of one workout vs. many candidate
	 * activities). Backs the on-demand activity -&gt; workout-library endpoint, and the
	 * ingest-time auto-match tie-break for when more than one same-day/sport
	 * {@code ScheduledWorkout} candidate exists.
	 *
	 * <p>Every candidate is assumed to share the same athlete and sport as {@code activity}
	 * (both callers filter for this already), so the power-zone reference is looked up once and
	 * reused rather than recomputed per workout. Non-scannable candidates and ones outside
	 * {@code toleranceSeconds} (the validated default, widenable per-call for the on-demand
	 * endpoint - e.g. a distance-based activity's actual moving time can legitimately fall well
	 * outside a fixed-duration workout's planned duration) are skipped, cheapest check first: the
	 * persisted {@code workout.getDuration()} column (no extra query) before
	 * {@code scannabilityErrorFor}/{@code buildCurveFor} (which fetch {@code WorkoutStep} rows
	 * via {@link #fetchWithSteps}), so a large library doesn't pay a per-candidate steps fetch
	 * for every candidate. Returns best match first.
	 */
	public List<RankedWorkout> rankWorkoutsForActivity(List<Workout> workouts, Activity activity) {
		return rankWorkoutsForActivity(workouts, activity, DURATION_TOLERANCE_SECONDS);
	}

	/** Same as {@link #rankWorkoutsForActivity(List, Activity)}, but with an explicit duration
	 * tolerance instead of the default. */
	public List<RankedWorkout> rankWorkoutsForActivity(List<Workout> workouts, Activity activity, int toleranceSeconds) {
		List<Record> records = recordRepository.findByActivityIdOrderByT(activity.getId());
		if (records.isEmpty()) {
			return List.of();
		}

		Double reference = null;
		List<RankedWorkout> results = new ArrayList<>();
		for (Workout workout : workouts) {
			if (Math.abs(workout.getDuration() - activity.getMovingTime()) > toleranceSeconds) {
				continue;
			}
			Workout withSteps = fetchWithSteps(workout.getId());
			if (scannabilityErrorFor(withSteps, Set.of()) != null) {
				continue;
			}
			if (reference == null) {
				reference = referenceFor(withSteps);
			}
			List<Flattened> flattened = WorkoutStepFlattener.flatten(withSteps);
			CorrelationResult result;
			if (needsDistanceWalk(flattened)) {
				result = correlateRecordsByStepBoundary(withSteps, records, reference, Set.of());
			}
			else {
				List<Segment> curve = buildCurveFor(withSteps, reference, Set.of());
				result = correlateRecords(curve, records);
			}
			if (result == null) {
				continue;
			}
			results.add(new RankedWorkout(workout, result.correlation(), result.coverage(), result.impliedFtp()));
		}

		results.sort((a, b) -> Double.compare(b.correlation(), a.correlation()));
		return results;
	}

	/** Backs {@code GET /v1/activities/{id}/workout-match-candidates} - ranks every workout the
	 * activity's athlete owns in the activity's sport against the activity's actual power
	 * stream, via {@link #rankWorkoutsForActivity}. No archived/status concept exists on
	 * {@code Workout} to filter on further; a flat/unstructured template self-excludes via
	 * {@code pearson}'s zero-variance guard, so it doesn't need filtering out here either. */
	public List<RankedWorkout> findCandidateWorkoutsForActivity(Activity activity) {
		return findCandidateWorkoutsForActivity(activity, DURATION_TOLERANCE_SECONDS);
	}

	/** Same as {@link #findCandidateWorkoutsForActivity(Activity)}, but with an explicit
	 * duration tolerance instead of the default - lets the on-demand endpoint widen the
	 * pre-filter per-call (e.g. a distance-based activity's actual moving time can legitimately
	 * fall well outside a fixed-duration workout's planned duration). */
	public List<RankedWorkout> findCandidateWorkoutsForActivity(Activity activity, int toleranceSeconds) {
		List<Workout> candidates =
				workoutRepository.findByCreatedByIdAndSport(activity.getAthlete().getId(), activity.getSport());
		return rankWorkoutsForActivity(candidates, activity, toleranceSeconds);
	}

	private static double round4(double v) {
		return Math.round(v * 10000.0) / 10000.0;
	}

	/** Orchestrates a full scan: candidate selection (same athlete, same sport as the workout,
	 * {@code workout IS NULL} - a real query against the whole Activity table, so it doesn't
	 * share the truncated-history gap an ad-hoc paginated search would have), the duration
	 * pre-filter, then a correlation pass per surviving candidate. Persists a
	 * WorkoutMatchScanCandidate row for every candidate that passed the duration filter,
	 * however low its score, so the full ranked list stays inspectable. Progress is committed
	 * via {@link WorkoutMatchScanProgressUpdater} (its own {@code REQUIRES_NEW} transaction per
	 * update) so a polling GET sees it mid-scan, not only once this whole method returns.
	 */
	@Async
	public void runScan(String scanId) {
		runScanSync(scanId);
	}

	/** The synchronous body of {@link #runScan}, split out purely so tests can call it directly
	 * without the {@code @Async} proxy's own-thread timing - a common pattern for testing
	 * {@code @Async} methods, not a second production entry point. */
	public void runScanSync(String scanId) {
		WorkoutMatchScan scan = scanRepository.findById(scanId).orElseThrow();
		progressUpdater.markProcessing(scanId);

		try {
			Workout workout = fetchWithSteps(scan.getWorkout().getId());
			Set<StepKind> excludedKinds = scan.getExcludedStepKinds().stream().map(StepKind::fromWireValue).collect(Collectors.toSet());
			List<Flattened> flattened = WorkoutStepFlattener.flatten(workout);
			boolean distanceWalk = needsDistanceWalk(flattened);
			double reference = referenceFor(workout);
			List<Segment> curve = distanceWalk ? null : buildCurveFor(workout, reference, excludedKinds);
			// workout.getDuration(), not the curve's last entry: the curve can end before the
			// workout's real total duration whenever a trailing step (typically the cooldown) is
			// excluded - the activity recording still covers that phase in real time, so the
			// duration pre-filter below needs the true total, which the persisted column always
			// has regardless of exclusions.
			int totalPlannedDuration = workout.getDuration();
			String athleteId = workout.getCreatedBy().getId();
			Sport sport = workout.getSport();

			List<Activity> allCandidates = activityRepository.findByAthleteIdAndSportAndWorkoutIsNull(athleteId, sport);
			List<Activity> candidates = allCandidates.stream()
					.filter(a -> Math.abs(a.getMovingTime() - totalPlannedDuration) <= DURATION_TOLERANCE_SECONDS)
					.toList();
			progressUpdater.updateTotalCandidates(scanId, candidates.size());

			List<WorkoutMatchScanCandidate> rows = new ArrayList<>();
			int processed = 0;
			for (Activity activity : candidates) {
				CorrelationResult result;
				if (distanceWalk) {
					List<Record> records = recordRepository.findByActivityIdOrderByT(activity.getId());
					result = correlateRecordsByStepBoundary(workout, records, reference, excludedKinds);
				}
				else {
					result = correlateActivity(curve, activity);
				}
				if (result != null) {
					WorkoutMatchScanCandidate row = new WorkoutMatchScanCandidate();
					row.setScan(scan);
					row.setActivity(activity);
					row.setCorrelation(result.correlation());
					row.setDurationDiffSeconds(Math.abs(activity.getMovingTime() - totalPlannedDuration));
					row.setCoverage(result.coverage());
					row.setImpliedFtp(result.impliedFtp());
					rows.add(row);
				}
				processed++;
				progressUpdater.updateProcessedCandidates(scanId, processed);
			}
			candidateRepository.saveAll(rows);

			progressUpdater.markReady(scanId, Instant.now());
		}
		// Throwable, not Exception - same reasoning as ExportService.runExport: without this the
		// job is silently abandoned PROCESSING forever, with no way for the athlete to find out.
		catch (Throwable t) {
			log.error("Workout match scan {} failed", scanId, t);
			progressUpdater.markFailed(scanId, t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName(),
					Instant.now());
		}
	}
}
