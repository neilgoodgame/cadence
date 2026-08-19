package com.cadence.api.activities;

import com.cadence.api.activities.dto.DurationCurveResponse;
import com.cadence.api.common.RecomputeLockRegistry;
import com.cadence.api.common.error.ConflictException;
import com.cadence.api.security.AccessGuard;
import com.cadence.api.users.User;
import com.cadence.api.users.UserService;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class CurveController {

	private static final String LOCK_KIND = "curves";

	private final ActivityService activityService;
	private final DurationCurveRepository durationCurveRepository;
	private final DurationCurveRecomputeService recomputeService;
	private final UserService userService;
	private final AccessGuard accessGuard;
	private final RecomputeLockRegistry lockRegistry;
	private final Executor taskExecutor = Executors.newVirtualThreadPerTaskExecutor();

	public CurveController(ActivityService activityService, DurationCurveRepository durationCurveRepository,
			DurationCurveRecomputeService recomputeService, UserService userService, AccessGuard accessGuard,
			RecomputeLockRegistry lockRegistry) {
		this.activityService = activityService;
		this.durationCurveRepository = durationCurveRepository;
		this.recomputeService = recomputeService;
		this.userService = userService;
		this.accessGuard = accessGuard;
		this.lockRegistry = lockRegistry;
	}

	@GetMapping("/v1/activities/{id}/curves")
	public DurationCurveResponse getCurves(@PathVariable String id,
			@RequestParam(defaultValue = "power") DurationCurveMetric metric) {
		Activity activity = activityService.getActivity(id);
		accessGuard.requireRead(activity.getAthlete().getId());
		return durationCurveRepository.findByActivityIdAndMetric(id, metric)
				.map(dc -> new DurationCurveResponse(dc.getMetric(), dc.getExtendsTo(), dc.getPoints()))
				.orElse(new DurationCurveResponse(metric, 0, Map.of()));
	}

	/** Backfills duration curves for every eligible activity - see DurationCurveRecomputeService's
	 * Javadoc for why this needs to exist at all (nothing else ever computes them for an activity
	 * that already exists). SSE, same shape as BestEffortController#recompute - see that
	 * method's Javadoc for why the timeout is 0 (no timeout) and why a lock is needed at all. */
	@PostMapping(value = "/v1/athletes/{id}/curves/recompute", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public SseEmitter recompute(@PathVariable String id) {
		accessGuard.requireWrite(id);
		if (!lockRegistry.tryAcquire(LOCK_KIND, id)) {
			throw new ConflictException("A duration-curve recompute is already running for this athlete.");
		}
		User athlete = userService.getById(id);
		SseEmitter emitter = new SseEmitter(0L);

		taskExecutor.execute(() -> {
			try {
				int processed = recomputeService.recomputeAll(athlete, (current, total) -> sendProgress(emitter, current, total));
				emitter.send(SseEmitter.event()
						.name("done")
						.data("{\"processed\":" + processed + "}"));
				emitter.complete();
			} catch (Exception e) {
				emitter.completeWithError(e);
			} finally {
				lockRegistry.release(LOCK_KIND, id);
			}
		});

		return emitter;
	}

	private void sendProgress(SseEmitter emitter, int current, int total) {
		try {
			emitter.send(SseEmitter.event()
					.data("{\"current\":" + current + ",\"total\":" + total + "}"));
		} catch (Exception e) {
			// Client disconnected, or the emitter already completed some other way - either
			// way nothing here should interrupt the recompute loop still in progress.
		}
	}
}
