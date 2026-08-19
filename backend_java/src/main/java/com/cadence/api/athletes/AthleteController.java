package com.cadence.api.athletes;

import com.cadence.api.activities.Activity;
import com.cadence.api.activities.ActivityRepository;
import com.cadence.api.activities.DerivedStatsRecomputeService;
import com.cadence.api.activities.TssRecomputeService;
import com.cadence.api.athletes.dto.AthleteUpdateRequest;
import com.cadence.api.athletes.dto.AthleteUpdateResponse;
import com.cadence.api.athletes.dto.ThresholdHistoryListResponse;
import com.cadence.api.athletes.dto.ThresholdSummaryEntry;
import com.cadence.api.athletes.dto.ZoneSetReplaceRequest;
import com.cadence.api.athletes.dto.ZoneSetReplaceResponse;
import com.cadence.api.athletes.dto.ZoneSetResponse;
import com.cadence.api.common.RecomputeLockRegistry;
import com.cadence.api.common.error.ConflictException;
import com.cadence.api.common.error.NotFoundException;
import com.cadence.api.common.paging.DataListResponse;
import com.cadence.api.security.AccessGuard;
import com.cadence.api.users.User;
import com.cadence.api.users.UserMapper;
import com.cadence.api.users.UserService;
import com.cadence.api.users.dto.UserResponse;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class AthleteController {

	private final UserService userService;
	private final UserMapper userMapper;
	private final AthleteService athleteService;
	private final ZoneService zoneService;
	private final FitnessService fitnessService;
	private final TssRecomputeService tssRecomputeService;
	private final DerivedStatsRecomputeService derivedStatsRecomputeService;
	private final ThresholdHistoryService thresholdHistoryService;
	private final ActivityRepository activityRepository;
	private final AccessGuard accessGuard;
	private final RecomputeLockRegistry lockRegistry;
	private final Executor taskExecutor = Executors.newVirtualThreadPerTaskExecutor();

	public AthleteController(UserService userService, UserMapper userMapper, AthleteService athleteService,
			ZoneService zoneService, FitnessService fitnessService, TssRecomputeService tssRecomputeService,
			DerivedStatsRecomputeService derivedStatsRecomputeService, ThresholdHistoryService thresholdHistoryService,
			ActivityRepository activityRepository, AccessGuard accessGuard, RecomputeLockRegistry lockRegistry) {
		this.userService = userService;
		this.userMapper = userMapper;
		this.athleteService = athleteService;
		this.zoneService = zoneService;
		this.fitnessService = fitnessService;
		this.tssRecomputeService = tssRecomputeService;
		this.derivedStatsRecomputeService = derivedStatsRecomputeService;
		this.thresholdHistoryService = thresholdHistoryService;
		this.activityRepository = activityRepository;
		this.accessGuard = accessGuard;
		this.lockRegistry = lockRegistry;
	}

	@GetMapping("/v1/athletes/{id}")
	public UserResponse getAthlete(@PathVariable String id) {
		accessGuard.requireRead(id);
		return userMapper.toResponse(userService.getById(id));
	}

	@PatchMapping("/v1/athletes/{id}")
	public AthleteUpdateResponse updateAthlete(@PathVariable String id, @RequestBody AthleteUpdateRequest request) {
		accessGuard.requireWrite(id);
		User athlete = userService.getById(id);
		List<ZoneType> recomputed = athleteService.updateProfile(athlete, request);
		List<String> recomputedWire = recomputed.stream().map(ZoneType::wireValue).toList();
		return new AthleteUpdateResponse(userMapper.toResponse(athlete), recomputedWire);
	}

	@GetMapping("/v1/athletes/{id}/zones")
	public DataListResponse<ZoneSetResponse> listZones(
			@PathVariable String id, @RequestParam(required = false) String activityId) {
		accessGuard.requireRead(id);
		User athlete = userService.getById(id);
		// Optional: scope bike_power/run_power/pace's reference to one activity's own threshold
		// snapshot instead of the athlete's current profile - see ZoneService.referenceFor. Must
		// belong to this same athlete, same ownership check as any other athlete-scoped read.
		Activity activity = activityId == null ? null
				: activityRepository.findByIdAndAthleteId(activityId, id)
						.orElseThrow(() -> new NotFoundException("No such activity."));
		List<ZoneSetResponse> zones = zoneService.getAllOrCreate(athlete).stream()
				.map(zs -> new ZoneSetResponse(zs.getType(), zoneService.referenceFor(athlete, zs.getType(), activity), zs.getZones()))
				.toList();
		return new DataListResponse<>(zones);
	}

	@PutMapping("/v1/athletes/{id}/zones/{type}")
	public ZoneSetReplaceResponse replaceZoneSet(@PathVariable String id, @PathVariable ZoneType type,
			@Valid @RequestBody ZoneSetReplaceRequest request) {
		accessGuard.requireWrite(id);
		User athlete = userService.getById(id);
		zoneService.replaceZones(athlete, type, request.zones());
		Double reference = zoneService.referenceFor(athlete, type);
		return new ZoneSetReplaceResponse(type, reference, true);
	}

	@PostMapping("/v1/athletes/{id}/recompute-tss")
	public ResponseEntity<Map<String, Integer>> recomputeTss(@PathVariable String id) {
		accessGuard.requireWrite(id);
		User athlete = userService.getById(id);
		int updated = tssRecomputeService.recomputeForAthlete(athlete);
		return ResponseEntity.ok(Map.of("updated", updated));
	}

	/** See BestEffortController#recompute's Javadoc for why the timeout is 0 (a full account can
	 * legitimately take a long time - a fixed timeout eventually kills any slow-enough account)
	 * and why a per-athlete lock is needed (the background task outlives this connection, so a
	 * disconnected/stalled run can still be going when a retry starts a second one). */
	@PostMapping(value = "/v1/athletes/{id}/recompute-stats", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public SseEmitter recomputeStats(@PathVariable String id) {
		accessGuard.requireWrite(id);
		if (!lockRegistry.tryAcquire("derived-stats", id)) {
			throw new ConflictException("A derived-stats recompute is already running for this athlete.");
		}
		User athlete = userService.getById(id);
		SseEmitter emitter = new SseEmitter(0L);

		taskExecutor.execute(() -> {
			try {
				int updated = derivedStatsRecomputeService.recomputeForAthlete(athlete,
						(current, total) -> sendProgress(emitter, current, total));
				emitter.send(SseEmitter.event().name("done").data("{\"updated\":" + updated + "}"));
				emitter.complete();
			} catch (Exception e) {
				emitter.completeWithError(e);
			} finally {
				lockRegistry.release("derived-stats", id);
			}
		});

		return emitter;
	}

	private void sendProgress(SseEmitter emitter, int current, int total) {
		try {
			emitter.send(SseEmitter.event().data("{\"current\":" + current + ",\"total\":" + total + "}"));
		} catch (Exception e) {
			// Client disconnected, or the emitter already completed some other way - either
			// way nothing here should interrupt the recompute loop still in progress.
		}
	}

	/** The current value + previous value + staleness for all three fields at once - what the
	 * dashboard widget reads. No recompute - stale just means "will update on the next activity,
	 * or refresh below." */
	@GetMapping("/v1/athletes/{id}/thresholds")
	public Map<String, ThresholdSummaryEntry> getThresholds(@PathVariable String id) {
		accessGuard.requireRead(id);
		User athlete = userService.getById(id);
		return Map.of(
				ThresholdField.FTP.wireValue(), thresholdHistoryService.summaryFor(athlete, ThresholdField.FTP),
				ThresholdField.CRITICAL_RUN_POWER.wireValue(), thresholdHistoryService.summaryFor(athlete, ThresholdField.CRITICAL_RUN_POWER),
				ThresholdField.THRESHOLD_PACE.wireValue(), thresholdHistoryService.summaryFor(athlete, ThresholdField.THRESHOLD_PACE));
	}

	/** The full ledger for one field, most recent first - backs the history screen the dashboard
	 * widget's per-field links lead to. */
	@GetMapping("/v1/athletes/{id}/threshold-history")
	public ThresholdHistoryListResponse getThresholdHistory(@PathVariable String id, @RequestParam ThresholdField field) {
		accessGuard.requireRead(id);
		User athlete = userService.getById(id);
		return new ThresholdHistoryListResponse(field, thresholdHistoryService.ledgerFor(athlete, field));
	}

	/** The dashboard's "this will update on your next activity, or refresh now" manual action -
	 * synchronous, cheap (a single current-window scan for one field, same as the ingest hook). */
	@PostMapping("/v1/athletes/{id}/thresholds/refresh")
	public Map<String, ThresholdSummaryEntry> refreshThreshold(@PathVariable String id, @RequestParam ThresholdField field) {
		accessGuard.requireWrite(id);
		User athlete = userService.getById(id);
		thresholdHistoryService.refreshField(athlete, field);
		return getThresholds(id);
	}

	/** Rebuilds the entire history ledger for one field from scratch, replaying the athlete's
	 * activities oldest-first (see ThresholdHistoryService.rebuildHistory). For bootstrapping
	 * history on an existing account, or after changing the window/sanity-check settings. */
	@PostMapping(value = "/v1/athletes/{id}/recompute-threshold-history", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public SseEmitter recomputeThresholdHistory(@PathVariable String id, @RequestParam ThresholdField field) {
		accessGuard.requireWrite(id);
		// Locked per-field, not per-athlete - rebuilding two different fields' ledgers
		// concurrently doesn't race (see ThresholdHistoryService.rebuildHistory), only two
		// rebuilds of the *same* field would.
		String lockKind = "threshold-history-" + field.name();
		if (!lockRegistry.tryAcquire(lockKind, id)) {
			throw new ConflictException("A recompute for this field is already running for this athlete.");
		}
		User athlete = userService.getById(id);
		SseEmitter emitter = new SseEmitter(0L);

		taskExecutor.execute(() -> {
			try {
				int total = thresholdHistoryService.rebuildHistory(
						athlete, field, (current, totalCount) -> sendProgress(emitter, current, totalCount));
				emitter.send(SseEmitter.event().name("done").data("{\"total\":" + total + "}"));
				emitter.complete();
			} catch (Exception e) {
				emitter.completeWithError(e);
			} finally {
				lockRegistry.release(lockKind, id);
			}
		});

		return emitter;
	}

	@GetMapping("/v1/athletes/{id}/fitness")
	public DataListResponse<FitnessPoint> getFitnessTrend(@PathVariable String id,
			@RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to) {
		accessGuard.requireRead(id);
		LocalDate effectiveTo = to != null ? to : LocalDate.now();
		LocalDate effectiveFrom = from != null ? from : effectiveTo.minusDays(84);
		return new DataListResponse<>(fitnessService.computeFitnessSeries(id, effectiveFrom, effectiveTo));
	}
}
