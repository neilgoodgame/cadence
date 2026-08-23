package com.cadence.api.athletes;

import com.cadence.api.activities.BestEffort;
import com.cadence.api.activities.BestEffortKind;
import com.cadence.api.activities.BestEffortRepository;
import com.cadence.api.activities.BestEffortRecomputeService;
import com.cadence.api.athletes.dto.BestEffortListResponse;
import com.cadence.api.athletes.dto.BestEffortResponse;
import com.cadence.api.common.RecomputeLockRegistry;
import com.cadence.api.common.SseHeartbeat;
import com.cadence.api.common.error.ConflictException;
import com.cadence.api.security.AccessGuard;
import com.cadence.api.users.User;
import com.cadence.api.users.UserService;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class BestEffortController {

	private static final String LOCK_KIND = "best-efforts";
	private static final long HEARTBEAT_INTERVAL_SECONDS = 20;

	private final BestEffortRepository bestEffortRepository;
	private final BestEffortRecomputeService recomputeService;
	private final UserService userService;
	private final AccessGuard accessGuard;
	private final RecomputeLockRegistry lockRegistry;
	private final Executor taskExecutor = Executors.newVirtualThreadPerTaskExecutor();

	public BestEffortController(BestEffortRepository bestEffortRepository,
			BestEffortRecomputeService recomputeService, UserService userService,
			AccessGuard accessGuard, RecomputeLockRegistry lockRegistry) {
		this.bestEffortRepository = bestEffortRepository;
		this.recomputeService = recomputeService;
		this.userService = userService;
		this.accessGuard = accessGuard;
		this.lockRegistry = lockRegistry;
	}

	@GetMapping("/v1/athletes/{id}/best-efforts")
	public BestEffortListResponse listBestEfforts(@PathVariable String id,
			@RequestParam BestEffortKind kind,
			@RequestParam(defaultValue = "all") String period) {
		accessGuard.requireRead(id);
		// 4w/16w match BestEffortWindows.TRIM_PERIOD_DAYS exactly - querying the exact displayed
		// period directly (rather than fetching a wider bucket and narrowing client-side) avoids
		// capPerWindow discarding entries that are top-N within the narrower window but not
		// within the top-N of a wider one it happened to be fetched from.
		LocalDate since = switch (period) {
			case "4w" -> LocalDate.now().minusDays(28);
			case "3m" -> LocalDate.now().minusDays(90);
			case "16w" -> LocalDate.now().minusDays(112);
			case "1y" -> LocalDate.now().minusDays(365);
			default -> null;
		};
		List<BestEffort> efforts = since != null
				? bestEffortRepository.findByAthleteIdAndKindAndDateGreaterThanEqualOrderByWindowAscValueDesc(id, kind, since)
				: bestEffortRepository.findByAthleteIdAndKindOrderByWindowAscValueDesc(id, kind);

		int topN = userService.getById(id).getBestEffortTopN();
		List<BestEffort> capped = capPerWindow(efforts, kind == BestEffortKind.RUNNING_PACE, topN);

		List<BestEffortResponse> data = capped.stream()
				.map(e -> new BestEffortResponse(e.getWindow(), e.getValue(), e.getUnit(), e.getDate(), e.getActivity().getId()))
				.toList();
		return new BestEffortListResponse(kind, period, data);
	}

	/**
	 * Trim retains up to topN rows per window in EACH tracked period independently (see
	 * BestEffortComputeService#trimToTop), so a single date-filtered read can still return more
	 * than topN rows for one window - e.g. the top-10-of-112-days set and the top-10-of-365-days
	 * set can differ, and a query spanning both periods sees their union. This re-caps to the
	 * true top N by value (respecting direction) before returning, preserving the
	 * window-asc/value-desc order callers expect.
	 */
	public static List<BestEffort> capPerWindow(List<BestEffort> efforts, boolean lowerIsBetter, int topN) {
		if (topN <= 0) return efforts; // 0 = unlimited, matching trimToTop's own "0 = keep all"
		Map<String, List<BestEffort>> byWindow = efforts.stream()
				.collect(Collectors.groupingBy(BestEffort::getWindow, LinkedHashMap::new, Collectors.toList()));
		Comparator<BestEffort> byRank = lowerIsBetter
				? Comparator.comparingDouble(BestEffort::getValue)
				: Comparator.comparingDouble(BestEffort::getValue).reversed();
		List<BestEffort> capped = new ArrayList<>();
		for (List<BestEffort> windowEfforts : byWindow.values()) {
			capped.addAll(windowEfforts.stream().sorted(byRank).limit(topN).toList());
		}
		capped.sort(Comparator.comparing(BestEffort::getWindow)
				.thenComparing(Comparator.comparingDouble(BestEffort::getValue).reversed()));
		return capped;
	}

	@DeleteMapping("/v1/athletes/{id}/best-efforts/by-activity/{activityId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Transactional
	public void excludeActivity(@PathVariable String id, @PathVariable String activityId,
			@RequestParam BestEffortKind kind) {
		accessGuard.requireRead(id);
		bestEffortRepository.deleteByAthleteIdAndKindAndActivityId(id, kind, activityId);
	}

	/**
	 * A full account can legitimately take hours (thousands of activities, each its own DB
	 * round-trip) - seen for real in production. Two things that bit us there, both fixed here:
	 * <ul>
	 * <li>{@code SseEmitter}'s timeout is wall-clock, not an idle timer - a value long enough
	 * for a quick account still eventually kills a slow one. {@code 0L} means no timeout at
	 * all (see {@code jakarta.servlet.AsyncContext#setTimeout}'s Javadoc: "A timeout value of
	 * zero or less indicates no timeout" - Spring's SseEmitter passes its constructor value
	 * straight through to this).</li>
	 * <li>The background task isn't tied to this connection's lifecycle at all - closing the
	 * browser tab doesn't stop it. So a user who thinks a stalled/disconnected run died and
	 * clicks the button again can end up with two runs racing on the same athlete's rows
	 * (recomputeAll deletes-then-rewrites by id) - one loses with a Hibernate
	 * StaleStateException. {@link RecomputeLockRegistry} rejects the second one instead.</li>
	 * </ul>
	 */
	@PostMapping(value = "/v1/athletes/{id}/best-efforts/recompute", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public SseEmitter recompute(@PathVariable String id,
			@RequestParam(required = false) BestEffortKind kind) {
		accessGuard.requireWrite(id);
		if (!lockRegistry.tryAcquire(LOCK_KIND, id)) {
			throw new ConflictException("A best-efforts recompute is already running for this athlete.");
		}
		User athlete = userService.getById(id);
		SseEmitter emitter = new SseEmitter(0L);
		SseHeartbeat heartbeat = new SseHeartbeat(
				() -> emitter.send(SseEmitter.event().name("heartbeat").data("{}")), HEARTBEAT_INTERVAL_SECONDS);

		taskExecutor.execute(() -> {
			try {
				int processed = (kind != null)
						? recomputeService.recompute(athlete, kind, (current, total) -> sendProgress(emitter, current, total))
						: recomputeService.recomputeAll(athlete, (current, total) -> sendProgress(emitter, current, total));
				emitter.send(SseEmitter.event()
						.name("done")
						.data("{\"processed\":" + processed + "}"));
				emitter.complete();
			} catch (Exception e) {
				emitter.completeWithError(e);
			} finally {
				heartbeat.close();
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
			// Client disconnected (IOException), or the emitter already completed some other
			// way (e.g. IllegalStateException) - either way the emitter itself will settle on
			// its own; nothing here should interrupt the recompute loop still in progress.
		}
	}

	@PostMapping("/v1/athletes/{id}/best-efforts/trim")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void trim(@PathVariable String id) {
		accessGuard.requireWrite(id);
		User athlete = userService.getById(id);
		recomputeService.trimAll(athlete);
	}
}
