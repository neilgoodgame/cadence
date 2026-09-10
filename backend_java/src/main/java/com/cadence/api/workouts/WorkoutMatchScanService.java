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
 * <p>v1 only supports power-target, duration-based workouts (every flattened leaf step must
 * have {@code TargetType.POWER} and {@code StepEndType.TIME}) - pace-target workouts read too
 * noisily off GPS/treadmill speed to trust at the same confidence threshold validated for
 * power (no forced compliance mechanism the way ERG mode holds power steady), so they're
 * intentionally out of scope for now. Follow-up: validate against a real pace-based workout
 * before adding support.
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

	/** {@code null} if the workout can be scanned for matches; otherwise the reason it can't,
	 * suitable for a 400 response. */
	public String scannabilityError(String workoutId) {
		return scannabilityErrorFor(fetchWithSteps(workoutId));
	}

	private String scannabilityErrorFor(Workout workout) {
		List<Flattened> flattened = WorkoutStepFlattener.flatten(workout);
		if (flattened.isEmpty()) {
			return "This workout has no steps to scan against.";
		}
		for (Flattened f : flattened) {
			WorkoutStep step = f.step();
			if (step.getTargetType() != TargetType.POWER) {
				return "Only power-target workouts can be scanned for matches right now.";
			}
			if (step.getEndType() != StepEndType.TIME || step.getDuration() == null) {
				return "Only duration-based steps (not distance- or manual-ended) can be scanned for matches right now.";
			}
		}
		return null;
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
		return buildCurveFor(fetchWithSteps(workoutId));
	}

	private List<Segment> buildCurveFor(Workout workout) {
		User athlete = userService.getById(workout.getCreatedBy().getId());
		ZoneType zoneType = workout.getSport() == Sport.BIKE ? ZoneType.BIKE_POWER : ZoneType.RUN_POWER;
		Double reference = zoneService.referenceFor(athlete, zoneType);
		double effectiveReference = reference != null ? reference : DEFAULT_POWER_REFERENCE;

		List<Segment> curve = new ArrayList<>();
		int offset = 0;
		for (Flattened f : WorkoutStepFlattener.flatten(workout)) {
			WorkoutStep step = f.step();
			double low = step.getTargetLow() != null ? step.getTargetLow() : 0.0;
			double high = step.getTargetHigh() != null ? step.getTargetHigh() : low;
			double mid = (low + high) / 2;
			double pct = step.getPowerUnit() == PowerUnit.WATTS ? (mid / effectiveReference) * 100 : mid;
			curve.add(new Segment(offset, offset + step.getDuration(), pct));
			offset += step.getDuration();
		}
		return curve;
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
	 * inside the workout's planned duration). */
	public CorrelationResult correlateActivity(List<Segment> curve, Activity activity) {
		List<Record> records = recordRepository.findByActivityIdOrderByT(activity.getId());
		if (records.isEmpty()) {
			return null;
		}
		int startT = records.get(0).getT();
		List<Double> xs = new ArrayList<>();
		List<Double> ys = new ArrayList<>();
		for (Record r : records) {
			if (r.getPower() == null) {
				continue;
			}
			Double expected = sampleExpectedAt(curve, r.getT() - startT);
			if (expected != null) {
				xs.add(expected);
				ys.add((double) r.getPower());
			}
		}
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

		double coverage = (double) xs.size() / records.size();
		return new CorrelationResult(round4(r), round4(coverage), impliedFtp);
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
			List<Segment> curve = buildCurveFor(workout);
			int totalPlannedDuration = curve.isEmpty() ? 0 : curve.get(curve.size() - 1).endS();
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
				CorrelationResult result = correlateActivity(curve, activity);
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
