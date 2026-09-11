package com.cadence.api.workouts;

import com.cadence.api.common.error.NotFoundException;
import com.cadence.api.common.error.ValidationException;
import com.cadence.api.security.AccessGuard;
import com.cadence.api.workouts.dto.WorkoutMatchScanCandidateResponse;
import com.cadence.api.workouts.dto.WorkoutMatchScanCreateRequest;
import com.cadence.api.workouts.dto.WorkoutMatchScanResponse;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Mirrors {@code ExportController}'s POST-creates-a-job / GET-polls-it shape - see
 * {@link WorkoutMatchScan}'s Javadoc for why this is a background job rather than a
 * synchronous request. */
@RestController
public class WorkoutMatchScanController {

	private static final List<WorkoutMatchScanStatus> ACTIVE_STATUSES =
			List.of(WorkoutMatchScanStatus.QUEUED, WorkoutMatchScanStatus.PROCESSING);

	private static final List<String> DEFAULT_EXCLUDED_STEP_KINDS = List.of("warmup", "cool");

	private final WorkoutService workoutService;
	private final WorkoutMatchScanService workoutMatchScanService;
	private final WorkoutMatchScanRepository scanRepository;
	private final WorkoutMatchScanCandidateRepository candidateRepository;
	private final AccessGuard accessGuard;

	public WorkoutMatchScanController(WorkoutService workoutService, WorkoutMatchScanService workoutMatchScanService,
			WorkoutMatchScanRepository scanRepository, WorkoutMatchScanCandidateRepository candidateRepository,
			AccessGuard accessGuard) {
		this.workoutService = workoutService;
		this.workoutMatchScanService = workoutMatchScanService;
		this.scanRepository = scanRepository;
		this.candidateRepository = candidateRepository;
		this.accessGuard = accessGuard;
	}

	@PostMapping("/v1/workouts/{id}/match-scans")
	public ResponseEntity<WorkoutMatchScanResponse> createMatchScan(
			@PathVariable String id, @RequestBody(required = false) WorkoutMatchScanCreateRequest request) {
		Workout workout = workoutService.getWorkout(id);
		accessGuard.requireWrite(workout.getCreatedBy().getId());

		List<String> excludedStepKinds =
				request != null && request.excludedStepKinds() != null ? request.excludedStepKinds() : DEFAULT_EXCLUDED_STEP_KINDS;
		Set<StepKind> excludedKinds;
		try {
			excludedKinds = excludedStepKinds.stream().map(StepKind::fromWireValue).collect(Collectors.toSet());
		} catch (IllegalArgumentException e) {
			throw new ValidationException("excludedStepKinds must each be one of warmup, block, rec, cool.", "excludedStepKinds");
		}

		String error = workoutMatchScanService.scannabilityError(id, excludedKinds);
		if (error != null) {
			throw new ValidationException(error, "workout");
		}

		// At most one active scan per workout - a re-POST while one is queued/processing just
		// hands back that scan's id rather than starting a duplicate, loosely mirroring
		// ExportJob's one-active-job-per-athlete constraint. Its kind selection is whatever the
		// FIRST of the concurrent requests set - a second request's selection is ignored in that
		// case, same as any other field would be.
		WorkoutMatchScan scan = scanRepository.findFirstByWorkoutIdAndStatusIn(id, ACTIVE_STATUSES).orElseGet(() -> {
			WorkoutMatchScan created = new WorkoutMatchScan();
			created.setWorkout(workout);
			created.setExcludedStepKinds(excludedStepKinds);
			WorkoutMatchScan saved = scanRepository.save(created);
			workoutMatchScanService.runScan(saved.getId());
			return saved;
		});

		return ResponseEntity.status(HttpStatus.ACCEPTED)
				.header(HttpHeaders.LOCATION, "/v1/workouts/" + id + "/match-scans/" + scan.getId())
				.header("Retry-After", "5")
				.body(toResponse(scan));
	}

	@GetMapping("/v1/workouts/{id}/match-scans/{scanId}")
	public ResponseEntity<WorkoutMatchScanResponse> getMatchScan(@PathVariable String id, @PathVariable String scanId) {
		// Ownership check goes through workoutService.getWorkout(id) (the path variable, already
		// the workout id) rather than scan.getWorkout() - the latter is itself a lazy relation,
		// so calling anything beyond .getId() on it (e.g. .getCreatedBy()) would throw
		// LazyInitializationException once this call's own implicit transaction closes.
		Workout workout = workoutService.getWorkout(id);
		accessGuard.requireRead(workout.getCreatedBy().getId());
		WorkoutMatchScan scan = scanRepository.findByIdAndWorkoutId(scanId, id)
				.orElseThrow(() -> new NotFoundException("No such match scan."));

		boolean inFlight = ACTIVE_STATUSES.contains(scan.getStatus());
		return inFlight
				? ResponseEntity.ok().header("Retry-After", "5").body(toResponse(scan))
				: ResponseEntity.ok(toResponse(scan));
	}

	private WorkoutMatchScanResponse toResponse(WorkoutMatchScan scan) {
		List<WorkoutMatchScanCandidateResponse> candidates = scan.getStatus() == WorkoutMatchScanStatus.READY
				? candidateRepository.findByScanIdOrderByCorrelationDescFetchActivity(scan.getId()).stream()
						.map(WorkoutMatchScanController::toCandidateResponse)
						.toList()
				: List.of();
		return new WorkoutMatchScanResponse(scan.getId(), scan.getWorkout().getId(), scan.getStatus(),
				scan.getExcludedStepKinds(), scan.getTotalCandidates(), scan.getProcessedCandidates(),
				scan.getErrorMessage(), scan.getCreatedAt(), scan.getCompletedAt(), candidates);
	}

	private static WorkoutMatchScanCandidateResponse toCandidateResponse(WorkoutMatchScanCandidate candidate) {
		var activity = candidate.getActivity();
		return new WorkoutMatchScanCandidateResponse(activity.getId(), activity.getName(),
				activity.getStartDate().atZone(ZoneOffset.UTC).toLocalDate(), candidate.getCorrelation(),
				candidate.getDurationDiffSeconds(), candidate.getCoverage(), candidate.getImpliedFtp(),
				activity.getMovingTime(), activity.getAvgPower());
	}
}
